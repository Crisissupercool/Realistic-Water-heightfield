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
 * (inject or reservoir entries).
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
			int[] layout = {U, S, S, S, S, S, S, S, S, S, S, S, S, S, S};
			programs = new ComputeProgram[]{
				new ComputeProgram("hf_flux.comp", layout),
				new ComputeProgram("hf_depth.comp", layout),
				new ComputeProgram("hf_mesh.comp", layout),
				new ComputeProgram("hf_probe.comp", layout),
				new ComputeProgram("mesh_args.comp", U, S),
				new ComputeProgram("hf_remap.comp", layout),
				new ComputeProgram("hf_inject.comp", layout),
				new ComputeProgram("hf_reservoir.comp", layout)
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

	/** A rebuilt tile: new pages (geo, links) to upload, then water to remap from the old pages, which are freed afterwards. */
	private record Rebuilt(int[] oldPages, int[] newPages, VkBuf staging) {
	}

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
		for (int i = 0; i < Vk.FRAME_RING; i++) {
			this.debugReadback[i] = VkBuf.readback(DEBUG_SPANS * 12L);
			this.injectRing[i] = VkBuf.upload(MAX_INJECT * 8L, VkBuf.STORAGE);
			this.syncReadback[i] = VkBuf.readback((long) MAX_SYNC_TILES * HfTileGrid.MAX_LAYERS * this.ps * 4L);
		}
		this.reservoirBuf = VkBuf.device(MAX_RESERVOIRS * 16L, 0);
		this.tilesDirty = true;
		programs();
	}

	/** Size of the GPU allocation, for /water hf stats. */
	long bytes() {
		return (long) this.grid.maxPages * this.ps * SPAN_BYTES + (long) this.grid.maxFluxPages * this.ps * FLUX_BYTES + this.quadCapacity * 80L;
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
		int[] old = this.grid.replace(tile, t);
		if (old == null) {
			return false;
		}
		int[] pages = new int[HfTileGrid.MAX_LAYERS];
		System.arraycopy(this.grid.spanPage, tile * 4, pages, 0, HfTileGrid.MAX_LAYERS);
		this.rebuilt.add(new Rebuilt(old, pages, this.stage(t, null)));
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
			Desc.ssbo(this.meshCounters), Desc.ssbo(view), Desc.ssbo(this.pageOwner), Desc.ssbo(aux));
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
			}
			Vk.barrier(cb);
			for (Rebuilt r : this.rebuilt) {
				int[] o = r.oldPages();
				int[] q = r.newPages();
				this.bind(p[5], cb, ubo, ring, this.stats);
				p[5].push(cb, this.grid.tn, this.grid.cpb, this.grid.yMin, 0, o[0], o[1], o[2], o[3], q[0], q[1], q[2], q[3], 0, 0, 0, 0);
				p[5].dispatch(cb, ComputeProgram.groups(this.ps, 256), 1, 1);
			}
			Vk.barrier(cb);
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
	void record(VkCommandBuffer cb, Desc ubo, HfTileGrid.Activation act, int steps, float dt) {
		ComputeProgram[] p = programs();
		int ring = Vk.ringSlot();
		if (!this.statsCleared) {
			this.stats.fill(cb, 0);
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
			int[] pc = this.push(n, dt, WaterSettings.gravity, this.cell, keep, WaterSettings.hfMaxSpeed, WaterSettings.hfRenderDepth,
				WaterSettings.hfMinFlowDepth, 0.0F, 0, 0, 0, 0);
			int groups = n * this.groupsPerPage;
			for (int s = 0; s < steps; s++) {
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
	}

	/** Rebuilds the surface mesh only when the water moved, tiles changed or the render threshold changed. */
	void buildMesh(VkCommandBuffer cb, Desc ubo) {
		if (!this.meshDirty && this.meshRenderDepth == WaterSettings.hfRenderDepth && this.meshDebug == WaterSettings.hfDebugMesh) {
			return;
		}
		this.meshDirty = false;
		this.meshRenderDepth = WaterSettings.hfRenderDepth;
		this.meshDebug = WaterSettings.hfDebugMesh;
		ComputeProgram[] p = programs();
		int ring = Vk.ringSlot();
		this.meshCounters.fill(cb, 0);
		Vk.barrier(cb);
		int pages = this.grid.maxPages;
		this.bind(p[2], cb, ubo, ring, this.stats);
		p[2].push(cb, this.push(pages, 0.0F, 0.0F, this.cell, this.originY, 0.0F, WaterSettings.hfRenderDepth, 0.0F, WaterSettings.hfDebugMesh,
			this.quadCapacity, 0, 0, 0));
		p[2].dispatch(cb, pages * this.groupsPerPage, 1, 1);
		Vk.barrier(cb);
		p[4].bind(cb, ubo, Desc.ssbo(this.meshCounters));
		p[4].push(cb, 0, this.quadCapacity);
		p[4].dispatch(cb, 1, 1, 1);
		Vk.barrier(cb);
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
				+ "max depth %.2f  max speed %.2f b/s  |  %s",
			g.count, g.maxTiles, g.usedSpanPages(), g.maxPages, g.usedFluxPages(), g.maxFluxPages, usedMb, this.bytes() / 1.0e6, g.droppedSpans,
			volume, drift, maxDepth, g.activeMax(g.maxSpeed), motion);
	}

	@Override
	public void close() {
		for (Added a : this.added) {
			Vk.destroyLater(a.staging());
		}
		for (Rebuilt r : this.rebuilt) {
			Vk.destroyLater(r.staging());
		}
		this.added.clear();
		this.rebuilt.clear();
		List<AutoCloseable> all = new ArrayList<>(List.of(
			this.geo, this.depth, this.flux, this.links, this.tileInfo, this.pageOwner, this.stats, this.verts, this.quads, this.meshCounters));
		all.add(this.reservoirBuf);
		for (int i = 0; i < Vk.FRAME_RING; i++) {
			all.add(this.injectRing[i]);
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
