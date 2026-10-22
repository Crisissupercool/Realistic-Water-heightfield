package me.skaffy.client.water;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import me.skaffy.client.vk.ComputeProgram;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.Program;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * GPU side of the sparse heightfield: span pages (packed floor/ceil, depth, links: 12 bytes per span), flux pages (32 bytes per
 * span, only for simulated tiles), per-page stats and the render mesh. {@link HfTileGrid} decides which pages exist and which tiles
 * are simulated; this class uploads new and rebuilt tiles, remaps water after block edits, records the flux, depth, mesh and probe
 * dispatches, and reads the stats back.
 *
 * <p>All heightfield shaders share one binding layout: 0 Globals UBO, 1 geo, 2 depth, 3 flux, 4 links, 5 page list, 6 tile info,
 * 7 tile active flags, 8 stats, 9 verts, 10 quads, 11 mesh counters, 12 view state (probe only), 13 page owners, 14 aux
 * (inject or reservoir entries), 15 foam (two halves per flux page span, swapped every solver step), 16 vertex flow (mesh),
 * 17 spray particles (two halves of {@link #SPRAY_MAX}), 18 spray counters and draw args, 19 tile lookup (slot per tile around
 * the origin, see {@link #LOOKUP}).
 */
final class HfDomain implements AutoCloseable {
	/** Two vec4 per (span page, group of 256 spans): (volume, max depth, max speed, max rate), (border bits, 0, 0, 0). */
	private static final int STATS_BYTES = 32;
	private static final int SPAN_BYTES = 12;
	private static final int FLUX_BYTES = HfTopology.LINKS * 4;

	private static ComputeProgram[] programs;

	private static ComputeProgram[] programs() {
		if (programs == null) {
			int U = Desc.UBO;
			int S = Desc.SSBO;
			int[] layout = {U, S, S, S, S, S, S, S, S, S, S, S, S, S, S, S, S, S, S, S};
			programs = new ComputeProgram[]{
				new ComputeProgram("hf_flux.comp", layout),
				new ComputeProgram("hf_depth.comp", layout),
				new ComputeProgram("hf_mesh.comp", layout),
				new ComputeProgram("hf_probe.comp", layout),
				new ComputeProgram("mesh_args.comp", U, S),
				new ComputeProgram("hf_remap.comp", layout),
				new ComputeProgram("hf_inject.comp", layout),
				new ComputeProgram("hf_reservoir.comp", layout),
				new ComputeProgram("hf_sample.comp", layout),
				new ComputeProgram("hf_spray_update.comp", layout),
				new ComputeProgram("hf_spray_args.comp", layout)
			};
		}
		return programs;
	}

	static void closePrograms() {
		if (programs != null) {
			for (ComputeProgram p : programs) {
				p.close();
			}
			programs = null;
		}
	}

	/** A new tile's pages (geo, links, depth) waiting for upload. */
	private record Added(int tile, int[] pages, VkBuf staging) {
	}

	/**
	 * A rebuilt tile: new pages (geo, links) to upload, then water to remap from the old pages, which are freed afterwards.
	 * fluxPages: the flux pages the tile kept through the rebuild (NONE where it has none), whose flow the remap moves too.
	 */
	private record Rebuilt(int tile, int[] oldPages, int[] newPages, int[] fluxPages, int[] freshFlux, VkBuf staging, int[] oldGeo,
		int oldLayers, int[] newGeo, int newLayers) {
	}

	/**
	 * Edit probe (WaterSettings.hfEditProbe, a diagnostic): the depth of a rebuilt tile read back just before the remap, just after
	 * it and {@link #PROBE_LATER} frames later, so a block edit that makes the water jump is reported in chat with where it
	 * happened, at which stage (the remap itself or the solver afterwards) and how the column's spans changed.
	 */
	private static final int PROBE_LATER = 12;
	private static final int MAX_PROBES = 4;

	private static final class EditProbe {
		final Rebuilt r;
		final VkBuf readback;
		final int version;
		long laterFrame;
		long readyFrame = Long.MAX_VALUE;

		EditProbe(Rebuilt r, VkBuf readback, int version) {
			this.r = r;
			this.readback = readback;
			this.version = version;
		}
	}

	private final List<EditProbe> probes = new ArrayList<>();
	private long frameNo;
	private @org.jspecify.annotations.Nullable String probeText;

	final HfTileGrid grid;
	final int originX;
	final int originZ;
	final int originY;
	final int cellsPerBlock;
	final float cell;
	final int quadCapacity;
	double initialVolume;

	private final int ps;
	private final int groupsPerPage;
	private final VkBuf geo;
	private final VkBuf depth;
	private final VkBuf flux;
	/** Foam per flux-page span, twice: one half is read and the other written each step (see hf_depth.comp). */
	private final VkBuf foam;
	private final long foamHalf;
	private int foamRead;
	private final VkBuf links;
	private final VkBuf tileInfo;
	private final VkBuf pageOwner;
	private final VkBuf stats;
	private final VkBuf[] statsReadback = new VkBuf[Vk.FRAME_RING];
	private final int[][] statsPages = new int[Vk.FRAME_RING][];
	private final VkBuf[] listRing = new VkBuf[Vk.FRAME_RING];
	private final VkBuf[] activeRing = new VkBuf[Vk.FRAME_RING];
	final VkBuf verts;
	final VkBuf quads;
	final VkBuf meshCounters;
	/** Water velocity per mesh vertex (two halves), for flow-mapped shading. */
	final VkBuf vertFlow;

	/**
	 * Spray (see hf_spray_update.comp): whitewater particles in two halves of SPRAY_MAX, in the layout of the particle regions'
	 * whitewater (pos.xyz relative to the origin in blocks with life in w, vel.xyz with the type in w: 0 droplet, 3 mist), so the
	 * whitewater pass draws them. Each frame the particles alive in one half move into the other; the sheets emit into it too.
	 */
	static final int SPRAY_MAX = 65_536;
	/** Water drops (splashes, drawn as water), a second set after the spray: two halves of DROP_MAX. */
	static final int DROP_MAX = 16_384;
	/** Byte offsets of the spray and drop draw arguments (6 vertices, count, 0, first particle) in {@link #sprayCounters}. */
	static final long SPRAY_ARGS = 16;
	static final long DROP_ARGS = 32;
	/** Side of the tile lookup grid (tiles), centered on the origin tile. Must match hf_spray_update.comp. */
	static final int LOOKUP = 64;
	final VkBuf spray;
	final VkBuf sprayCounters;
	private final VkBuf tileLookup;
	private int sprayCur;
	private float sprayDt;
	/** Splash bursts waiting: {x, y, z (relative to the origin), speed, radius, count, mode, delay in seconds}. */
	private final List<float[]> bursts = new ArrayList<>();
	private final List<Added> added = new ArrayList<>();
	private final List<Rebuilt> rebuilt = new ArrayList<>();
	private boolean tilesDirty;
	private boolean statsCleared;

	int lastSteps;
	float lastDt;
	int lastRebuilt;
	private boolean meshDirty = true;
	private float meshRenderDepth = Float.NaN;
	private int meshDebug;
	private float meshFoam = Float.NaN;

	// /water hf column: a few spans copied back to the CPU for inspection.
	private static final int DEBUG_SPANS = 20;
	private final VkBuf[] debugReadback = new VkBuf[Vk.FRAME_RING];
	private final String[][] debugLabels = new String[Vk.FRAME_RING][];
	private final int[][] debugSpans = new int[Vk.FRAME_RING][];
	private int[] debugRequest;
	private String[] debugRequestLabels;
	private String debugText;

	// Water added or removed at given spans (placed blocks, floods, captures into existing tiles), applied at the next record.
	private static final int MAX_INJECT = 65_536;
	/** Pending injects as {tile, col, y, depth}; resolved to spans only when applied, after any rebuild of the tile. */
	private final List<float[]> injects = new ArrayList<>();
	private final VkBuf[] injectRing = new VkBuf[Vk.FRAME_RING];
	// Reservoirs (see HfTileGrid), re-uploaded when they change.
	private static final int MAX_RESERVOIRS = 65_536;
	private final VkBuf reservoirBuf;
	private int reservoirCount;

	// Depth read back for writing water into blocks: per ring slot, the tiles copied and where.
	static final int MAX_SYNC_TILES = 4;
	private final VkBuf[] syncReadback = new VkBuf[Vk.FRAME_RING];
	private final int[][] syncTiles = new int[Vk.FRAME_RING][];
	private final int[][] syncVersions = new int[Vk.FRAME_RING][];

	// Gameplay sample at the player (see hf_sample.comp), read back a few frames later.
	private final VkBuf sampleBuf;
	private final VkBuf[] sampleReadback = new VkBuf[Vk.FRAME_RING];
	private final boolean[] samplePending = new boolean[Vk.FRAME_RING];

	/** A tile's depth as read back from the GPU, per {@code layer * pageSpans + col}. */
	record TileDepth(int tile, int version, float[] depth) {
	}

	HfDomain(HfTileGrid grid, int maxQuads) {
		this.grid = grid;
		this.originX = grid.originX;
		this.originZ = grid.originZ;
		this.originY = grid.yMin;
		this.cellsPerBlock = grid.cpb;
		this.cell = grid.cell;
		this.ps = grid.pageSpans;
		this.groupsPerPage = grid.pageSpans / 256;
		this.quadCapacity = Math.clamp(maxQuads, 65_536, 4_000_000);
		long spans = (long) grid.maxPages * this.ps;

		this.geo = VkBuf.device(spans * 4L, 0);
		this.depth = VkBuf.device(spans * 4L, 0);
		this.links = VkBuf.device(spans * 4L, 0);
		this.flux = VkBuf.device((long) grid.maxFluxPages * this.ps * FLUX_BYTES, 0);
		this.foamHalf = (long) grid.maxFluxPages * this.ps;
		this.foam = VkBuf.device(this.foamHalf * 8L, 0);
		this.tileInfo = VkBuf.device((long) grid.maxTiles * 64L, 0);
		this.pageOwner = VkBuf.device((long) grid.maxPages * 4L, 0);
		long statsBytes = (long) grid.maxPages * this.groupsPerPage * STATS_BYTES;
		this.stats = VkBuf.device(statsBytes, 0);
		for (int i = 0; i < Vk.FRAME_RING; i++) {
			this.statsReadback[i] = VkBuf.readback(statsBytes);
			this.listRing[i] = VkBuf.upload((long) grid.maxTiles * HfTileGrid.MAX_LAYERS * 4L, VkBuf.STORAGE);
			this.activeRing[i] = VkBuf.upload((long) grid.maxTiles * 4L, VkBuf.STORAGE);
		}
		this.verts = VkBuf.device(this.quadCapacity * 4L * 16L, 0);
		this.quads = VkBuf.device(this.quadCapacity * 16L, 0);
		this.meshCounters = VkBuf.device(64, VkBuf.INDIRECT);
		this.vertFlow = VkBuf.device(this.quadCapacity * 4L * 4L, 0);
		this.spray = VkBuf.device(2L * (SPRAY_MAX + DROP_MAX) * 32L, 0);
		this.sprayCounters = VkBuf.device(64, VkBuf.INDIRECT);
		this.tileLookup = VkBuf.device((long) LOOKUP * LOOKUP * 4L, 0);
		for (int i = 0; i < Vk.FRAME_RING; i++) {
			this.debugReadback[i] = VkBuf.readback(DEBUG_SPANS * 12L);
			this.injectRing[i] = VkBuf.upload(MAX_INJECT * 8L, VkBuf.STORAGE);
			this.syncReadback[i] = VkBuf.readback((long) MAX_SYNC_TILES * HfTileGrid.MAX_LAYERS * this.ps * 4L);
		}
		this.reservoirBuf = VkBuf.device(MAX_RESERVOIRS * 16L, 0);
		this.sampleBuf = VkBuf.device(32L, 0);
		for (int i = 0; i < Vk.FRAME_RING; i++) {
			this.sampleReadback[i] = VkBuf.readback(32L);
		}
		this.tilesDirty = true;
		programs();
	}

	/** Size of the GPU allocation, for /water hf stats. */
	long bytes() {
		return (long) this.grid.maxPages * this.ps * SPAN_BYTES + (long) this.grid.maxFluxPages * this.ps * (FLUX_BYTES + 8L)
			+ this.quadCapacity * 96L + 2L * (SPRAY_MAX + DROP_MAX) * 32L;
	}

	private VkBuf stage(HfTileGrid.Tile t, float[] initialDepth) {
		int n = t.layersUsed * this.ps;
		VkBuf staging = VkBuf.upload(n * 12L, 0);
		ByteBuffer b = staging.mapped();
		for (int i = 0; i < n; i++) {
			b.putInt(i * 4, t.geo[i]);
			b.putInt(n * 4 + i * 4, t.links[i]);
			b.putFloat(n * 8 + i * 4, initialDepth != null && HfTileGrid.validGeo(t.geo[i]) ? initialDepth[i] : 0.0F);
		}
		staging.flush(0, n * 12L);
		return staging;
	}

	/**
	 * Puts a built tile into the pool; its data is uploaded at the start of the next {@link #record}.
	 *
	 * @param initialDepth depth per {@code layer * pageSpans + col}, or null for a dry tile
	 * @return the tile index, or {@link HfTileGrid#NONE} when the tile table or the span pages are full
	 */
	int addTile(int tx, int tz, HfTileGrid.Tile t, float[] initialDepth) {
		int tile = this.grid.add(tx, tz, t);
		if (tile == HfTileGrid.NONE) {
			return tile;
		}
		double volume = 0.0;
		float maxDepth = 0.0F;
		if (initialDepth != null) {
			for (int i = 0; i < t.layersUsed * this.ps; i++) {
				if (HfTileGrid.validGeo(t.geo[i])) {
					volume += (double) initialDepth[i] * this.cell * this.cell;
					maxDepth = Math.max(maxDepth, initialDepth[i]);
				}
			}
		}
		this.grid.volume[tile] = volume;
		this.grid.maxDepth[tile] = maxDepth;
		this.initialVolume += volume;
		int[] pages = new int[t.layersUsed];
		for (int l = 0; l < t.layersUsed; l++) {
			pages[l] = this.grid.spanPage[tile * 4 + l];
		}
		this.added.add(new Added(tile, pages, this.stage(t, initialDepth)));
		this.tilesDirty = true;
		this.meshDirty = true;
		return tile;
	}

	/** Swaps in a rebuilt topology for a tile; the water is remapped into it on the GPU at the next {@link #record}. */
	boolean rebuildTile(int tile, HfTileGrid.Tile t) {
		int[] oldGeo = this.grid.tileGeo[tile];
		int oldLayers = this.grid.layersUsed[tile];
		int[] old = this.grid.replace(tile, t);
		if (old == null) {
			return false;
		}
		int[] pages = new int[HfTileGrid.MAX_LAYERS];
		System.arraycopy(this.grid.spanPage, tile * 4, pages, 0, HfTileGrid.MAX_LAYERS);
		int[] fluxPages = new int[HfTileGrid.MAX_LAYERS];
		System.arraycopy(this.grid.fluxPage, tile * 4, fluxPages, 0, HfTileGrid.MAX_LAYERS);
		this.rebuilt.add(new Rebuilt(tile, old, pages, fluxPages, this.grid.lastFreshFlux, this.stage(t, null), oldGeo, oldLayers, t.geo,
			t.layersUsed));
		this.tilesDirty = true;
		this.meshDirty = true;
		return true;
	}

	private void copyPages(VkCommandBuffer cb, VkBuf staging, int[] pages, int n, boolean withDepth) {
		long bytes = (long) this.ps * 4L;
		for (int l = 0; l < n; l++) {
			long dst = pages[l] * bytes;
			staging.copyTo(cb, this.geo, l * bytes, dst, bytes);
			staging.copyTo(cb, this.links, (n + l) * bytes, dst, bytes);
			if (withDepth) {
				staging.copyTo(cb, this.depth, (2L * n + l) * bytes, dst, bytes);
			}
		}
	}

	private int[] push(int count, float a, float b, float c, float d, float e, float f, float g, float h, int i, int j, int k, int m) {
		return new int[]{
			this.grid.tn, this.grid.cpb, this.grid.yMin, count,
			Program.f(a), Program.f(b), Program.f(c), Program.f(d),
			Program.f(e), Program.f(f), Program.f(g), Program.f(h),
			i, j, k, m
		};
	}

	private void bind(ComputeProgram p, VkCommandBuffer cb, Desc ubo, int ring, VkBuf view) {
		this.bind(p, cb, ubo, ring, view, this.reservoirBuf);
	}

	private void bind(ComputeProgram p, VkCommandBuffer cb, Desc ubo, int ring, VkBuf view, VkBuf aux) {
		p.bind(cb, ubo, Desc.ssbo(this.geo), Desc.ssbo(this.depth), Desc.ssbo(this.flux), Desc.ssbo(this.links), Desc.ssbo(this.listRing[ring]),
			Desc.ssbo(this.tileInfo), Desc.ssbo(this.activeRing[ring]), Desc.ssbo(this.stats), Desc.ssbo(this.verts), Desc.ssbo(this.quads),
			Desc.ssbo(this.meshCounters), Desc.ssbo(view), Desc.ssbo(this.pageOwner), Desc.ssbo(aux), Desc.ssbo(this.foam),
			Desc.ssbo(this.vertFlow), Desc.ssbo(this.spray), Desc.ssbo(this.sprayCounters), Desc.ssbo(this.tileLookup));
	}

	/**
	 * Adds (or with a negative amount removes) water depth in the span of column {@code col} that contains height y. Applied at
	 * the next {@link #record}, after new tiles are uploaded; wakes the tile. Counts as intended, so the volume drift stays meaningful.
	 */
	boolean inject(int tile, int col, float y, float addDepth) {
		if (this.grid.layerAt(tile, col, y) == HfTileGrid.NONE) {
			return false;
		}
		this.injects.add(new float[]{tile, col, y, addDepth});
		this.grid.quiet[tile] = 0.0F;
		double dv = (double) addDepth * this.cell * this.cell;
		this.grid.volume[tile] += dv;
		this.initialVolume += dv;
		this.meshDirty = true;
		return true;
	}

	/** Samples the water at world point (x, y, z) for gameplay; {@link #takeSample} returns it a few frames later. */
	void sample(VkCommandBuffer cb, Desc ubo, double x, double y, double z) {
		int[] at = this.grid.locate(x, z);
		if (at == null) {
			return;
		}
		int ring = Vk.ringSlot();
		ComputeProgram[] p = programs();
		this.bind(p[8], cb, ubo, ring, this.stats, this.sampleBuf);
		p[8].push(cb, this.push(at[1], (float) y, 0.0F, this.cell, at[0], 0.0F, 0.0F, 0.0F, 0.0F, 0, 0, 0, 0));
		p[8].dispatch(cb, 1, 1, 1);
		Vk.barrier(cb);
		this.sampleBuf.copyTo(cb, this.sampleReadback[ring], 0, 0, 32L);
		this.samplePending[ring] = true;
		Vk.barrier(cb);
	}

	/** {u, w, surface, depth, found, floor} from the sample recorded {@link Vk#FRAME_RING} frames ago, or null. */
	float[] takeSample() {
		int ring = Vk.ringSlot();
		if (!this.samplePending[ring]) {
			return null;
		}
		this.samplePending[ring] = false;
		VkBuf rb = this.sampleReadback[ring];
		rb.invalidate(0, 32L);
		ByteBuffer b = rb.mapped();
		return new float[]{b.getFloat(0), b.getFloat(4), b.getFloat(8), b.getFloat(12), b.getFloat(16), b.getFloat(20)};
	}

	/** Copies the depth of up to {@link #MAX_SYNC_TILES} tiles back to the CPU; {@link #takeDepths} returns them a few frames later. */
	void requestDepths(VkCommandBuffer cb, int[] tiles) {
		int ring = Vk.ringSlot();
		int n = Math.min(tiles.length, MAX_SYNC_TILES);
		long tileBytes = (long) HfTileGrid.MAX_LAYERS * this.ps * 4L;
		int[] versions = new int[n];
		for (int i = 0; i < n; i++) {
			int tile = tiles[i];
			versions[i] = this.grid.version[tile];
			for (int l = 0; l < this.grid.layersUsed[tile]; l++) {
				long src = (long) this.grid.spanPage[tile * 4 + l] * this.ps * 4L;
				this.depth.copyTo(cb, this.syncReadback[ring], src, i * tileBytes + l * this.ps * 4L, this.ps * 4L);
			}
		}
		this.syncTiles[ring] = java.util.Arrays.copyOf(tiles, n);
		this.syncVersions[ring] = versions;
		Vk.barrier(cb);
	}

	/** Depth read back for the tiles requested {@link Vk#FRAME_RING} frames ago (empty when none). */
	List<TileDepth> takeDepths() {
		int ring = Vk.ringSlot();
		int[] tiles = this.syncTiles[ring];
		List<TileDepth> out = new ArrayList<>();
		if (tiles == null) {
			return out;
		}
		this.syncTiles[ring] = null;
		long tileBytes = (long) HfTileGrid.MAX_LAYERS * this.ps * 4L;
		VkBuf rb = this.syncReadback[ring];
		rb.invalidate(0, tiles.length * tileBytes);
		ByteBuffer b = rb.mapped();
		for (int i = 0; i < tiles.length; i++) {
			int n = this.grid.layersUsed[tiles[i]] * this.ps;
			float[] d = new float[n];
			for (int k = 0; k < n; k++) {
				d[k] = b.getFloat((int) (i * tileBytes) + k * 4);
			}
			out.add(new TileDepth(tiles[i], this.syncVersions[ring][i], d));
		}
		return out;
	}

	private void flushInjectsAndReservoirs(VkCommandBuffer cb, Desc ubo, int ring) {
		ComputeProgram[] p = programs();
		if (!this.injects.isEmpty()) {
			java.util.LinkedHashMap<Integer, Float> merged = new java.util.LinkedHashMap<>();
			// At most MAX_INJECT entries per frame; the rest wait for the next frames.
			List<float[]> batch = this.injects.subList(0, Math.min(MAX_INJECT, this.injects.size()));
			for (float[] e : batch) {
				int tile = (int) e[0];
				int col = (int) e[1];
				int layer = this.grid.layerAt(tile, col, e[2]);
				if (layer != HfTileGrid.NONE) {
					merged.merge(this.grid.span(tile, layer, col), e[3], Float::sum);
				}
			}
			batch.clear();
			ByteBuffer b = this.injectRing[ring].mapped();
			int n = 0;
			for (var e : merged.entrySet()) {
				if (n >= MAX_INJECT) {
					break;
				}
				b.putInt(n * 8, e.getKey()).putFloat(n * 8 + 4, e.getValue());
				n++;
			}
			this.injectRing[ring].flush(0, n * 8L);
			this.bind(p[6], cb, ubo, ring, this.stats, this.injectRing[ring]);
			p[6].push(cb, this.push(n, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0, 0, 0, 0));
			p[6].dispatch(cb, ComputeProgram.groups(n, 64), 1, 1);
			Vk.barrier(cb);
		}
		if (this.grid.reservoirsDirty) {
			this.grid.reservoirsDirty = false;
			var all = this.grid.reservoirs();
			int n = Math.min(all.size(), MAX_RESERVOIRS);
			if (n > 0) {
				VkBuf staging = VkBuf.upload(n * 16L, 0);
				ByteBuffer b = staging.mapped();
				int i = 0;
				for (float[] r : all) {
					if (i >= n) {
						break;
					}
					b.putInt(i * 16, (int) r[0]).putInt(i * 16 + 4, (int) r[1]).putFloat(i * 16 + 8, r[2]).putInt(i * 16 + 12, (int) r[3]);
					i++;
				}
				staging.flush(0, n * 16L);
				staging.copyTo(cb, this.reservoirBuf, 0, 0, n * 16L);
				Vk.destroyLater(staging);
				Vk.barrier(cb);
			}
			this.reservoirCount = n;
		}
	}

	/** Rebuilt tiles first (their old pages may be handed to new tiles this frame), then the remaps, then new tiles and the tables. */
	private void flushUploads(VkCommandBuffer cb, Desc ubo, int ring, int[] newFluxPages) {
		ComputeProgram[] p = programs();
		this.lastRebuilt = this.rebuilt.size();
		if (!this.rebuilt.isEmpty()) {
			for (Rebuilt r : this.rebuilt) {
				int n = 0;
				while (n < HfTileGrid.MAX_LAYERS && r.newPages()[n] != HfTileGrid.NONE) {
					n++;
				}
				this.copyPages(cb, r.staging(), r.newPages(), n, false);
				// New layers' flux pages start at rest (they may hold another tile's flow) before the remap fills them.
				for (int fp : r.freshFlux()) {
					this.flux.fill(cb, (long) fp * this.ps * FLUX_BYTES, (long) this.ps * FLUX_BYTES, 0);
					this.foam.fill(cb, (long) fp * this.ps * 4L, (long) this.ps * 4L, 0);
					this.foam.fill(cb, (this.foamHalf + (long) fp * this.ps) * 4L, (long) this.ps * 4L, 0);
				}
				if (WaterSettings.hfEditProbe && this.probes.size() < MAX_PROBES && r.oldGeo() != null) {
					EditProbe probe = new EditProbe(r, VkBuf.readback(3L * HfTileGrid.MAX_LAYERS * this.ps * 4L), this.grid.version[r.tile()]);
					this.copyProbe(cb, probe, r.oldPages(), 0);
					this.probes.add(probe);
				}
			}
			Vk.barrier(cb);
			for (Rebuilt r : this.rebuilt) {
				int[] o = r.oldPages();
				int[] q = r.newPages();
				this.bind(p[5], cb, ubo, ring, this.stats);
				int[] f = r.fluxPages();
				p[5].push(cb, this.grid.tn, this.grid.cpb, this.grid.yMin, (int) this.foamHalf, o[0], o[1], o[2], o[3], q[0], q[1], q[2], q[3],
					f[0], f[1], f[2], f[3]);
				p[5].dispatch(cb, ComputeProgram.groups(this.ps, 256), 1, 1);
			}
			Vk.barrier(cb);
			for (EditProbe probe : this.probes) {
				if (this.rebuilt.contains(probe.r)) {
					this.copyProbe(cb, probe, probe.r.newPages(), 1);
					probe.laterFrame = this.frameNo + PROBE_LATER;
				}
			}
			for (Rebuilt r : this.rebuilt) {
				this.grid.releaseSpanPages(r.oldPages());
				Vk.destroyLater(r.staging());
			}
			this.rebuilt.clear();
		}
		for (Added a : this.added) {
			this.copyPages(cb, a.staging(), a.pages(), a.pages().length, true);
			Vk.destroyLater(a.staging());
		}
		this.added.clear();
		for (int fp : newFluxPages) {
			this.flux.fill(cb, (long) fp * this.ps * FLUX_BYTES, (long) this.ps * FLUX_BYTES, 0);
			// A page's foam halves may still hold another tile's foam from before.
			this.foam.fill(cb, (long) fp * this.ps * 4L, (long) this.ps * 4L, 0);
			this.foam.fill(cb, (this.foamHalf + (long) fp * this.ps) * 4L, (long) this.ps * 4L, 0);
		}
		if (this.tilesDirty) {
			this.tilesDirty = false;
			HfTileGrid g = this.grid;
			int n = Math.max(g.count, 1);
			VkBuf staging = VkBuf.upload(n * 64L + g.maxPages * 4L, 0);
			ByteBuffer b = staging.mapped();
			for (int s = 0; s < g.count; s++) {
				int o = s * 64;
				for (int d = 0; d < 4; d++) {
					b.putInt(o + d * 4, g.nbr[s * 4 + d]);
					b.putInt(o + 32 + d * 4, g.spanPage[s * 4 + d]);
					b.putInt(o + 48 + d * 4, g.fluxPage[s * 4 + d]);
				}
				b.putInt(o + 16, g.tileX[s]).putInt(o + 20, g.tileZ[s]).putInt(o + 24, g.layersUsed[s]).putInt(o + 28, 0);
			}
			for (int pg = 0; pg < g.maxPages; pg++) {
				b.putInt(n * 64 + pg * 4, g.pageOwner[pg]);
			}
			staging.flush(0, n * 64L + g.maxPages * 4L);
			if (g.count > 0) {
				staging.copyTo(cb, this.tileInfo, 0, 0, g.count * 64L);
			}
			staging.copyTo(cb, this.pageOwner, n * 64L, 0, g.maxPages * 4L);
			Vk.destroyLater(staging);
			// Tile slot per tile coordinate around the origin, so spray can find the water under it.
			VkBuf lookup = VkBuf.upload((long) LOOKUP * LOOKUP * 4L, 0);
			ByteBuffer lb = lookup.mapped();
			for (int i = 0; i < LOOKUP * LOOKUP; i++) {
				lb.putInt(i * 4, -1);
			}
			for (int s = 0; s < g.count; s++) {
				int lx = g.tileX[s] + LOOKUP / 2;
				int lz = g.tileZ[s] + LOOKUP / 2;
				if (lx >= 0 && lz >= 0 && lx < LOOKUP && lz < LOOKUP) {
					lb.putInt((lx + LOOKUP * lz) * 4, s);
				}
			}
			lookup.flush(0, (long) LOOKUP * LOOKUP * 4L);
			lookup.copyTo(cb, this.tileLookup, 0, 0, (long) LOOKUP * LOOKUP * 4L);
			Vk.destroyLater(lookup);
		}
		Vk.barrier(cb);
	}

	/** Reads the stats this ring slot recorded {@link Vk#FRAME_RING} frames ago into the grid. Call once per frame before planning. */
	void readStats() {
		int ring = Vk.ringSlot();
		int[] entries = this.statsPages[ring];
		if (entries == null) {
			return;
		}
		this.statsPages[ring] = null;
		VkBuf rb = this.statsReadback[ring];
		rb.invalidate(0, (long) this.grid.maxPages * this.groupsPerPage * STATS_BYTES);
		ByteBuffer b = rb.mapped();
		HfTileGrid g = this.grid;
		// entries = {tile, page} pairs simulated in that frame; only those tiles get fresh numbers.
		for (int i = 0; i < entries.length; i += 2) {
			int tile = entries[i];
			g.volume[tile] = 0.0;
			g.maxDepth[tile] = 0.0F;
			g.maxSpeed[tile] = 0.0F;
			g.maxRate[tile] = 0.0F;
			g.border[tile] = 0;
		}
		for (int i = 0; i < entries.length; i += 2) {
			int tile = entries[i];
			int page = entries[i + 1];
			for (int k = 0; k < this.groupsPerPage; k++) {
				int o = (page * this.groupsPerPage + k) * STATS_BYTES;
				g.volume[tile] += b.getFloat(o);
				g.maxDepth[tile] = Math.max(g.maxDepth[tile], b.getFloat(o + 4));
				g.maxSpeed[tile] = Math.max(g.maxSpeed[tile], b.getFloat(o + 8));
				g.maxRate[tile] = Math.max(g.maxRate[tile], b.getFloat(o + 12));
				g.border[tile] |= b.getInt(o + 16);
			}
		}
	}

	/**
	 * Asks for the spans of the column at world (x, z) and its four neighbors; {@link #takeDebugText} returns them a few frames later.
	 * Returns false when no tile covers that point.
	 */
	boolean requestColumn(double x, double z) {
		HfTileGrid g = this.grid;
		double[][] at = {{0, 0}, {g.cell, 0}, {-g.cell, 0}, {0, g.cell}, {0, -g.cell}};
		String[] names = {"here", "+x", "-x", "+z", "-z"};
		int[] spans = new int[DEBUG_SPANS];
		String[] labels = new String[DEBUG_SPANS];
		java.util.Arrays.fill(spans, -1);
		boolean any = false;
		for (int i = 0; i < at.length; i++) {
			double px = x + at[i][0];
			double pz = z + at[i][1];
			int tx = g.tileOfBlockX((int) Math.floor(px));
			int tz = g.tileOfBlockZ((int) Math.floor(pz));
			int tile = g.slotAt(tx, tz);
			if (tile == HfTileGrid.NONE) {
				continue;
			}
			int lx = Math.clamp((int) Math.floor((px - g.originX - tx * (double) HfTileGrid.TILE_BLOCKS) * g.cpb), 0, g.tn - 1);
			int lz = Math.clamp((int) Math.floor((pz - g.originZ - tz * (double) HfTileGrid.TILE_BLOCKS) * g.cpb), 0, g.tn - 1);
			for (int l = 0; l < HfTileGrid.MAX_LAYERS; l++) {
				int sp = g.span(tile, l, lz * g.tn + lx);
				if (sp != HfTileGrid.NONE) {
					spans[i * 4 + l] = sp;
					labels[i * 4 + l] = String.format("%s (%.1f, %.1f) tile %d %s L%d", names[i],
						g.originX + tx * 16.0 + lx / (double) g.cpb, g.originZ + tz * 16.0 + lz / (double) g.cpb, tile,
						g.active[tile] ? "sim" : "sleep", l);
					any = true;
				}
			}
		}
		if (any) {
			this.debugRequest = spans;
			this.debugRequestLabels = labels;
		}
		return any;
	}

	/** The text for the last {@link #requestColumn}, once it has come back from the GPU; null until then. */
	String takeDebugText() {
		int ring = Vk.ringSlot();
		if (this.debugSpans[ring] != null) {
			VkBuf rb = this.debugReadback[ring];
			rb.invalidate(0, DEBUG_SPANS * 12L);
			ByteBuffer b = rb.mapped();
			StringBuilder sb = new StringBuilder("Heightfield column (floor / ceil / depth / surface, links +x -x +z -z as layer:slot):");
			for (int i = 0; i < DEBUG_SPANS; i++) {
				if (this.debugSpans[ring][i] < 0) {
					continue;
				}
				int geo = b.getInt(i * 12);
				float d = b.getFloat(i * 12 + 4);
				int links = b.getInt(i * 12 + 8);
				if (!HfTileGrid.validGeo(geo)) {
					continue;
				}
				float fl = this.grid.floorOf(geo);
				float ce = this.grid.ceilOf(geo);
				StringBuilder ls = new StringBuilder();
				for (int k = 0; k < 8; k++) {
					int nib = HfTileGrid.link(links, k);
					ls.append(nib == HfTileGrid.LINK_NONE ? "-" : (nib & 3) + ":" + (nib >> 2)).append(k % 2 == 1 ? "  " : " ");
				}
				sb.append(String.format("%n%s: %.2f / %s / %.3f / %.3f  [%s]", this.debugLabels[ring][i], fl,
					ce >= HfTopology.OPEN_CEIL ? "open" : String.format("%.2f", ce), d, Math.min(fl + d, ce), ls.toString().trim()));
			}
			this.debugSpans[ring] = null;
			this.debugText = sb.toString();
		}
		String t = this.debugText;
		this.debugText = null;
		if (this.probeText != null) {
			t = t == null ? this.probeText : t + "\n" + this.probeText;
			this.probeText = null;
		}
		return t;
	}

	/** Largest stable time step for the simulated tiles (shallow-water CFL condition). */
	float maxStableDt() {
		float waveSpeed = (float) Math.sqrt(WaterSettings.gravity * Math.max(this.grid.activeMax(this.grid.maxDepth), 0.01F))
			+ this.grid.activeMax(this.grid.maxSpeed);
		return WaterSettings.hfCfl * this.cell / Math.max(waveSpeed, 1.0e-3F);
	}

	/**
	 * Uploads new and rebuilt tiles, remaps water, zeroes new flux pages and runs {@code steps} solver steps over the activation's
	 * pages. Call every frame (with an empty activation and steps = 0 while paused) so uploads and per-frame tables stay current.
	 */
	void record(VkCommandBuffer cb, Desc ubo, HfTileGrid.Activation act, int steps, float dt, float sprayDt) {
		ComputeProgram[] p = programs();
		int ring = Vk.ringSlot();
		if (!this.statsCleared) {
			this.stats.fill(cb, 0);
			this.foam.fill(cb, 0);
			this.sprayCounters.fill(cb, 0);
			this.statsCleared = true;
		}
		if (act.newFluxPages.length > 0) {
			this.tilesDirty = true;
		}
		this.flushUploads(cb, ubo, ring, act.newFluxPages);
		this.flushInjectsAndReservoirs(cb, ubo, ring);

		int[] list = act.pages;
		ByteBuffer lb = this.listRing[ring].mapped();
		ByteBuffer ab = this.activeRing[ring].mapped();
		for (int i = 0; i < list.length; i++) {
			lb.putInt(i * 4, list[i]);
		}
		for (int s = 0; s < this.grid.count; s++) {
			ab.putInt(s * 4, this.grid.active[s] ? 1 : 0);
		}
		this.listRing[ring].flush(0, Math.max(4L, list.length * 4L));
		this.activeRing[ring].flush(0, Math.max(4L, this.grid.count * 4L));

		int n = list.length;
		if (steps > 0 && n > 0) {
			float keep = (float) Math.pow(WaterSettings.hfDamping, dt * 60.0F) / (1.0F + WaterSettings.hfFriction * dt);
			float foamKeep = (float) Math.exp(-dt / Math.max(WaterSettings.hfFoamLife, 0.05F));
			int groups = n * this.groupsPerPage;
			for (int s = 0; s < steps; s++) {
				int read = (int) (this.foamRead * this.foamHalf);
				int write = (int) ((1 - this.foamRead) * this.foamHalf);
				this.foamRead ^= 1;
				int[] pc = this.push(n, dt, WaterSettings.gravity, this.cell, keep, WaterSettings.hfMaxSpeed, WaterSettings.hfRenderDepth,
					WaterSettings.hfMinFlowDepth, foamKeep, read, write, 0, 0);
				this.bind(p[0], cb, ubo, ring, this.stats);
				p[0].push(cb, pc);
				p[0].dispatch(cb, groups, 1, 1);
				Vk.barrier(cb);
				this.bind(p[1], cb, ubo, ring, this.stats);
				p[1].push(cb, pc);
				p[1].dispatch(cb, groups, 1, 1);
				Vk.barrier(cb);
				if (this.reservoirCount > 0) {
					this.bind(p[7], cb, ubo, ring, this.stats, this.reservoirBuf);
					p[7].push(cb, this.push(this.reservoirCount, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0, 0, 0, 0));
					p[7].dispatch(cb, ComputeProgram.groups(this.reservoirCount, 64), 1, 1);
					Vk.barrier(cb);
				}
			}
			this.stats.copyTo(cb, this.statsReadback[ring], 0, 0, (long) this.grid.maxPages * this.groupsPerPage * STATS_BYTES);
			int[] entries = new int[n * 2];
			for (int i = 0; i < n; i++) {
				int tile = list[i] >> 2;
				entries[i * 2] = tile;
				entries[i * 2 + 1] = this.grid.spanPage[tile * 4 + (list[i] & 3)];
			}
			this.statsPages[ring] = entries;
			Vk.barrier(cb);
			this.meshDirty = true;
			this.lastSteps = steps;
		} else {
			this.lastSteps = 0;
		}
		if (this.debugRequest != null) {
			for (int i = 0; i < DEBUG_SPANS; i++) {
				int sp = this.debugRequest[i];
				if (sp >= 0) {
					this.geo.copyTo(cb, this.debugReadback[ring], sp * 4L, i * 12L, 4);
					this.depth.copyTo(cb, this.debugReadback[ring], sp * 4L, i * 12L + 4, 4);
					this.links.copyTo(cb, this.debugReadback[ring], sp * 4L, i * 12L + 8, 4);
				}
			}
			this.debugSpans[ring] = this.debugRequest;
			this.debugLabels[ring] = this.debugRequestLabels;
			this.debugRequest = null;
			Vk.barrier(cb);
		}
		this.lastDt = dt;
		this.updateSpray(cb, ubo, ring, sprayDt);
		this.advanceProbes(cb);
	}

	/** Burst modes of hf_spray_update.comp. */
	static final int BURST_SPRAY = 1;
	static final int BURST_CROWN = 2;
	static final int BURST_JET = 3;

	/**
	 * A body hit the water at (x, y, z) (world): {@code count} particles are thrown after {@code delay} seconds, as white droplets
	 * from a ring of {@code radius} (BURST_SPRAY), water drops from the cavity's rim (BURST_CROWN) or a jet of water drops up from
	 * the middle (BURST_JET), at up to {@code speed}.
	 */
	void sprayBurst(int mode, double x, double y, double z, float speed, float radius, int count, float delay) {
		if (count > 0 && this.bursts.size() < 16) {
			this.bursts.add(new float[]{(float) (x - this.originX), (float) (y - this.originY), (float) (z - this.originZ), speed, radius, count, mode,
				delay});
		}
	}

	/** Moves the live spray into the other half (bursts first), so the mesh pass can add this frame's emission to it. */
	private void updateSpray(VkCommandBuffer cb, Desc ubo, int ring, float frameDt) {
		ComputeProgram[] p = programs();
		int src = this.sprayCur;
		int dst = 1 - src;
		this.sprayCounters.fill(cb, dst * 4L, 4L, 0);
		this.sprayCounters.fill(cb, (2L + dst) * 4L, 4L, 0);
		Vk.barrier(cb);
		this.bind(p[9], cb, ubo, ring, this.stats);
		for (java.util.Iterator<float[]> it = this.bursts.iterator(); it.hasNext(); ) {
			float[] b = it.next();
			b[7] -= frameDt;
			if (b[7] > 0.0F) {
				continue;
			}
			it.remove();
			int n = Math.min((int) b[5], 4096);
			p[9].push(cb, this.push(n, 0.0F, WaterSettings.gravity, b[3], b[4], b[0], b[1], b[2], b[6], src, dst, (int) this.frameNo * 7919 + n * 31 + (int) b[6], 0));
			p[9].dispatch(cb, ComputeProgram.groups(n, 256), 1, 1);
		}
		for (int set = 0; set < 2; set++) {
			p[9].push(cb, this.push(0, frameDt, WaterSettings.gravity, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, src, dst, (int) this.frameNo, set));
			p[9].dispatch(cb, (set == 0 ? SPRAY_MAX : DROP_MAX) / 256, 1, 1);
		}
		Vk.barrier(cb);
		this.sprayCur = dst;
		this.sprayDt = frameDt;
	}

	/** Draw arguments for the current spray half (after the mesh pass added its emission). */
	private void sprayArgs(VkCommandBuffer cb, Desc ubo) {
		ComputeProgram[] p = programs();
		this.bind(p[10], cb, ubo, Vk.ringSlot(), this.stats);
		p[10].push(cb, this.push(0, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, this.sprayCur, 0, 0, 0));
		p[10].dispatch(cb, 1, 1, 1);
		Vk.barrier(cb);
	}

	private void copyProbe(VkCommandBuffer cb, EditProbe probe, int[] pages, int region) {
		long layerBytes = (long) this.ps * 4L;
		for (int l = 0; l < HfTileGrid.MAX_LAYERS; l++) {
			if (pages[l] != HfTileGrid.NONE) {
				this.depth.copyTo(cb, probe.readback, pages[l] * layerBytes, (region * HfTileGrid.MAX_LAYERS + l) * layerBytes, layerBytes);
			}
		}
	}

	/** Takes the later readback of probed tiles, and reports the ones whose readbacks have all arrived. */
	private void advanceProbes(VkCommandBuffer cb) {
		this.frameNo++;
		if (this.probes.isEmpty()) {
			return;
		}
		boolean copied = false;
		for (java.util.Iterator<EditProbe> it = this.probes.iterator(); it.hasNext(); ) {
			EditProbe probe = it.next();
			int tile = probe.r.tile();
			if (probe.readyFrame == Long.MAX_VALUE && probe.laterFrame > 0 && this.frameNo >= probe.laterFrame) {
				if (this.grid.version[tile] != probe.version) {
					// Rebuilt again before the later look: report what the remap did without it.
					probe.readyFrame = this.frameNo + Vk.FRAME_RING + 1;
					probe.laterFrame = -1;
				} else {
					int[] pages = new int[HfTileGrid.MAX_LAYERS];
					System.arraycopy(this.grid.spanPage, tile * 4, pages, 0, HfTileGrid.MAX_LAYERS);
					this.copyProbe(cb, probe, pages, 2);
					copied = true;
					probe.readyFrame = this.frameNo + Vk.FRAME_RING + 1;
				}
			} else if (this.frameNo >= probe.readyFrame) {
				it.remove();
				String text = this.analyzeProbe(probe);
				Vk.destroyLater(probe.readback);
				if (text != null) {
					this.probeText = this.probeText == null ? text : this.probeText + "\n" + text;
				}
			}
		}
		if (copied) {
			Vk.barrier(cb);
		}
	}

	/** Highest water surface of one column over its spans (water deeper than 0.01), or NaN when it holds none. */
	private float columnTop(int[] geo, int layers, ByteBuffer b, int region, int col, float[] spans) {
		float top = Float.NaN;
		int n = 0;
		for (int l = 0; l < layers; l++) {
			int g = geo[l * this.ps + col];
			if (!HfTileGrid.validGeo(g)) {
				continue;
			}
			float d = b.getFloat(((region * HfTileGrid.MAX_LAYERS + l) * this.ps + col) * 4);
			float fl = this.grid.floorOf(g);
			if (spans != null && n < 4) {
				spans[n * 3] = fl;
				spans[n * 3 + 1] = Math.min(this.grid.ceilOf(g), 9999.0F);
				spans[n * 3 + 2] = d;
				n++;
			}
			if (d > 0.01F) {
				top = Float.isNaN(top) ? fl + d : Math.max(top, fl + d);
			}
		}
		if (spans != null) {
			for (int k = n; k < 4; k++) {
				spans[k * 3] = Float.NaN;
			}
		}
		return top;
	}

	private static String spansText(float[] s) {
		StringBuilder b = new StringBuilder();
		for (int k = 0; k < 4 && !Float.isNaN(s[k * 3]); k++) {
			b.append(String.format("%s%.2f-%s:%.2f", k > 0 ? ", " : "", s[k * 3], s[k * 3 + 1] >= 9999.0F ? "open" : String.format("%.2f", s[k * 3 + 1]),
				s[k * 3 + 2]));
		}
		return b.length() == 0 ? "none" : b.toString();
	}

	private @org.jspecify.annotations.Nullable String analyzeProbe(EditProbe probe) {
		Rebuilt r = probe.r;
		boolean later = probe.laterFrame >= 0;
		VkBuf rb = probe.readback;
		rb.invalidate(0, 3L * HfTileGrid.MAX_LAYERS * this.ps * 4L);
		ByteBuffer b = rb.mapped();
		int tn = this.grid.tn;
		int cpb = this.grid.cpb;
		int changed = 0;
		int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		float worstRemap = 0.0F;
		float worstLater = 0.0F;
		int remapCol = -1;
		int laterCol = -1;
		double volOld = 0.0;
		double volNew = 0.0;
		double volLater = 0.0;
		float area = this.cell * this.cell;
		for (int col = 0; col < this.ps; col++) {
			boolean differs = false;
			for (int l = 0; l < HfTileGrid.MAX_LAYERS && !differs; l++) {
				int a = l < r.oldLayers() ? r.oldGeo()[l * this.ps + col] : HfTileGrid.INVALID_GEO;
				int c = l < r.newLayers() ? r.newGeo()[l * this.ps + col] : HfTileGrid.INVALID_GEO;
				differs = (HfTileGrid.validGeo(a) || HfTileGrid.validGeo(c)) && a != c;
			}
			int bx = col % tn / cpb;
			int bz = col / tn / cpb;
			if (differs) {
				changed++;
				box[0] = Math.min(box[0], bx);
				box[1] = Math.min(box[1], bz);
				box[2] = Math.max(box[2], bx);
				box[3] = Math.max(box[3], bz);
			}
			for (int l = 0; l < HfTileGrid.MAX_LAYERS; l++) {
				if (l < r.oldLayers() && HfTileGrid.validGeo(r.oldGeo()[l * this.ps + col])) {
					volOld += b.getFloat((l * this.ps + col) * 4) * area;
				}
				if (l < r.newLayers() && HfTileGrid.validGeo(r.newGeo()[l * this.ps + col])) {
					volNew += b.getFloat(((HfTileGrid.MAX_LAYERS + l) * this.ps + col) * 4) * area;
					if (later) {
						volLater += b.getFloat(((2 * HfTileGrid.MAX_LAYERS + l) * this.ps + col) * 4) * area;
					}
				}
			}
			float oldTop = this.columnTop(r.oldGeo(), r.oldLayers(), b, 0, col, null);
			float newTop = this.columnTop(r.newGeo(), r.newLayers(), b, 1, col, null);
			float base = Float.isNaN(oldTop) ? Float.NaN : oldTop;
			if (!Float.isNaN(newTop)) {
				float rise = Float.isNaN(base) ? newTop - this.grid.floorOf(this.firstValid(r.newGeo(), r.newLayers(), col)) : newTop - base;
				if (rise > worstRemap) {
					worstRemap = rise;
					remapCol = col;
				}
			}
			if (later) {
				float laterTop = this.columnTop(r.newGeo(), r.newLayers(), b, 2, col, null);
				if (!Float.isNaN(laterTop) && !Float.isNaN(base) && laterTop - base > worstLater) {
					worstLater = laterTop - base;
					laterCol = col;
				}
			}
		}
		// Only surface jumps are reported: the volume changes with the edit (a placed block takes its cell's water) and, later, with
		// the water flowing in and out of the tile, which is normal.
		if (worstRemap < 0.4F && worstLater < 0.4F) {
			return null;
		}
		int ox = this.originX + this.grid.tileX[r.tile()] * HfTileGrid.TILE_BLOCKS;
		int oz = this.originZ + this.grid.tileZ[r.tile()] * HfTileGrid.TILE_BLOCKS;
		StringBuilder sb = new StringBuilder(String.format("[edit probe] tile at %d %d: %d columns changed shape%s; volume %.2f -> %.2f after remap%s",
			ox, oz, changed, changed > 0 ? String.format(" (blocks x %d..%d, z %d..%d)", ox + box[0], ox + box[2], oz + box[1], oz + box[3]) : "",
			volOld, volNew, later ? String.format(" -> %.2f after %d frames", volLater, PROBE_LATER) : ""));
		int[] cols = {remapCol, laterCol};
		float[] rises = {worstRemap, worstLater};
		String[] what = {"remap", PROBE_LATER + " frames later"};
		for (int k = 0; k < 2; k++) {
			int col = cols[k];
			if (col < 0 || rises[k] < 0.4F) {
				continue;
			}
			float[] so = new float[12];
			float[] sn = new float[12];
			float[] sl = new float[12];
			this.columnTop(r.oldGeo(), r.oldLayers(), b, 0, col, so);
			this.columnTop(r.newGeo(), r.newLayers(), b, 1, col, sn);
			if (later) {
				this.columnTop(r.newGeo(), r.newLayers(), b, 2, col, sl);
			}
			sb.append(String.format("%n  %s: surface +%.2f at x %.1f z %.1f | before (floor-ceil:depth) %s | after remap %s%s", what[k], rises[k],
				ox + (col % tn + 0.5) / cpb, oz + (col / tn + 0.5) / cpb, spansText(so), spansText(sn),
				later ? " | later " + spansText(sl) : ""));
		}
		return sb.toString();
	}

	private int firstValid(int[] geo, int layers, int col) {
		for (int l = 0; l < layers; l++) {
			if (HfTileGrid.validGeo(geo[l * this.ps + col])) {
				return geo[l * this.ps + col];
			}
		}
		return HfTileGrid.INVALID_GEO;
	}

	/** Rebuilds the surface mesh only when the water moved, tiles changed or the render threshold changed. */
	void buildMesh(VkCommandBuffer cb, Desc ubo) {
		if (!this.meshDirty && this.meshRenderDepth == WaterSettings.hfRenderDepth && this.meshDebug == WaterSettings.hfDebugMesh
			&& this.meshFoam == WaterSettings.hfFoam) {
			this.sprayArgs(cb, ubo);
			return;
		}
		this.meshDirty = false;
		this.meshRenderDepth = WaterSettings.hfRenderDepth;
		this.meshDebug = WaterSettings.hfDebugMesh;
		this.meshFoam = WaterSettings.hfFoam;
		ComputeProgram[] p = programs();
		int ring = Vk.ringSlot();
		this.meshCounters.fill(cb, 0);
		Vk.barrier(cb);
		int pages = this.grid.maxPages;
		this.bind(p[2], cb, ubo, ring, this.stats);
		// Sheets emit spray for the time the water moved this frame (none while paused or asleep).
		float emit = this.lastSteps * this.lastDt * Math.max(WaterSettings.hfSpray, 0.0F);
		p[2].push(cb, this.push(pages, WaterSettings.hfSurfaceDrop, WaterSettings.hfCurtainMinFlow, this.cell, this.originY,
			WaterSettings.hfFoam, WaterSettings.hfRenderDepth, emit, WaterSettings.hfDebugMesh,
			this.quadCapacity, (int) (this.foamRead * this.foamHalf), this.sprayCur, (int) this.frameNo));
		p[2].dispatch(cb, pages * this.groupsPerPage, 1, 1);
		Vk.barrier(cb);
		p[4].bind(cb, ubo, Desc.ssbo(this.meshCounters));
		p[4].push(cb, 0, this.quadCapacity);
		p[4].dispatch(cb, 1, 1, 1);
		Vk.barrier(cb);
		this.sprayArgs(cb, ubo);
	}

	/** Sets the underwater flag in {@code frameView} when the camera (world coordinates) is inside heightfield water. */
	void probe(VkCommandBuffer cb, Desc ubo, VkBuf frameView, double camX, double camY, double camZ) {
		int tx = this.grid.tileOfBlockX((int) Math.floor(camX));
		int tz = this.grid.tileOfBlockZ((int) Math.floor(camZ));
		int tile = this.grid.slotAt(tx, tz);
		if (tile == HfTileGrid.NONE) {
			return;
		}
		int tn = this.grid.tn;
		int lx = Math.clamp((int) Math.floor((camX - this.originX - tx * (double) HfTileGrid.TILE_BLOCKS) * this.cellsPerBlock), 0, tn - 1);
		int lz = Math.clamp((int) Math.floor((camZ - this.originZ - tz * (double) HfTileGrid.TILE_BLOCKS) * this.cellsPerBlock), 0, tn - 1);
		int[] sp = this.grid.spanPage;
		ComputeProgram[] p = programs();
		this.bind(p[3], cb, ubo, Vk.ringSlot(), frameView);
		p[3].push(cb, this.push(lz * tn + lx, (float) camY, 0.0F, 0.0F, 0.0F, 0.0F, WaterSettings.hfRenderDepth, 0.0F, 0.0F,
			sp[tile * 4], sp[tile * 4 + 1], sp[tile * 4 + 2], sp[tile * 4 + 3]));
		p[3].dispatch(cb, 1, 1, 1);
	}

	String statsLine() {
		HfTileGrid g = this.grid;
		double volume = g.totalVolume();
		double drift = this.initialVolume > 0.0 ? (volume - this.initialVolume) / this.initialVolume * 100.0 : 0.0;
		float maxDepth = 0.0F;
		for (int s = 0; s < g.count; s++) {
			maxDepth = Math.max(maxDepth, g.maxDepth[s]);
		}
		int awake = g.awakeCount(WaterSettings.hfSleepSeconds);
		String motion = g.activeList.length == 0 ? "asleep (settled)"
			: String.format("%d awake, %d simulated, %d steps x %.1f ms", awake, g.activeList.length, this.lastSteps, this.lastDt * 1000.0F);
		double usedMb = (g.usedSpanPages() * (double) this.ps * SPAN_BYTES + g.usedFluxPages() * (double) this.ps * FLUX_BYTES) / 1.0e6;
		return String.format(
			(this.reservoirCount > 0 ? "[" + this.reservoirCount + " reservoir cells add/remove water] " : "") + "heightfield (Y " + g.yMin + " to " + g.yMax + "): %d/%d tiles, pages %d/%d + flux %d/%d (%.1f of %.0f MB), %d spans dropped  |  volume %,.1f blocks (%+.3f%% since start)  |  "
				+ "max depth %.2f  max speed %.2f b/s  |  %s%s",
			g.count, g.maxTiles, g.usedSpanPages(), g.maxPages, g.usedFluxPages(), g.maxFluxPages, usedMb, this.bytes() / 1.0e6, g.droppedSpans,
			volume, drift, maxDepth, g.activeMax(g.maxSpeed), motion,
			g.foreignKeptOpen > 0 ? String.format("  |  edits kept %,d non-realistic fluid blocks open", g.foreignKeptOpen) : "");
	}

	@Override
	public void close() {
		for (EditProbe probe : this.probes) {
			Vk.destroyLater(probe.readback);
		}
		this.probes.clear();
		for (Added a : this.added) {
			Vk.destroyLater(a.staging());
		}
		for (Rebuilt r : this.rebuilt) {
			Vk.destroyLater(r.staging());
		}
		this.added.clear();
		this.rebuilt.clear();
		List<AutoCloseable> all = new ArrayList<>(List.of(
			this.geo, this.depth, this.flux, this.foam, this.links, this.tileInfo, this.pageOwner, this.stats, this.verts, this.quads, this.meshCounters,
			this.vertFlow, this.spray, this.sprayCounters, this.tileLookup));
		all.add(this.reservoirBuf);
		all.add(this.sampleBuf);
		for (int i = 0; i < Vk.FRAME_RING; i++) {
			all.add(this.injectRing[i]);
			all.add(this.sampleReadback[i]);
			all.add(this.syncReadback[i]);
			all.add(this.debugReadback[i]);
			all.add(this.statsReadback[i]);
			all.add(this.listRing[i]);
			all.add(this.activeRing[i]);
		}
		for (AutoCloseable c : all) {
			Vk.destroyLater(c);
		}
	}
}
