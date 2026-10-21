package me.skaffy.client.water;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * CPU side of the sparse heightfield (docs/HEIGHTFIELD_DESIGN.md, sections 12-13): which 16x16-block tiles exist, which GPU
 * pages hold them, who neighbors whom, and which tiles are awake, simulated or asleep this frame. Free of Minecraft and Vulkan
 * types so the tests in tools/hf-cpu-test drive exactly the same bookkeeping as the game.
 *
 * <p><b>Pages.</b> A page holds one layer of one tile: {@code pageSpans = tn * tn} spans, one per column. A tile only gets pages
 * for the layers its columns actually use (open ground: 1, a village: usually 2). Span index = {@code page * pageSpans + col}.
 * Per span a page stores 12 bytes: packed floor/ceil, depth and links. The 32 bytes of flux per span live in separate flux pages
 * that a tile only holds while it is simulated, so a sleeping tile costs 12 bytes per span and layer.
 *
 * <p><b>Geometry</b> is packed as {@code floorU | ceilU << 16}, both in sub-block units above {@link #yMin}
 * ({@code ceilU = 0xFFFF}: open to the sky; unused layer: {@code floorU = 0xFFFF, ceilU = 0}). Spans always start and end on
 * sub-voxel boundaries, so this is exact.
 *
 * <p><b>Links</b> are 4 bits, 8 per span in one int (slot k at bits 4k): {@code j << 2 | targetLayer}, or {@link #LINK_NONE}.
 * The target column follows from the slot's direction (k / 2: 0 = +x, 1 = -x, 2 = +z, 3 = -z) and the reverse slot is
 * {@code (dir ^ 1) * 2 + j}, so a link never needs patching when a neighbor tile appears or is rebuilt.
 */
final class HfTileGrid {
	static final int TILE_BLOCKS = 16;
	static final int NONE = -1;
	static final int MAX_LAYERS = 4;
	static final int LINK_NONE = 0xF;
	static final int OPEN_U = 0xFFFF;
	static final int INVALID_GEO = 0x0000FFFF;
	static final int[] DX = {1, -1, 0, 0};
	static final int[] DZ = {0, 0, 1, -1};

	/** Spans and links of one tile, laid out per page: index {@code layer * pageSpans + col}. */
	static final class Tile {
		final int layersUsed;
		final int[] geo;
		final int[] links;
		final int dropped;

		Tile(int layersUsed, int[] geo, int[] links, int dropped) {
			this.layersUsed = layersUsed;
			this.geo = geo;
			this.links = links;
			this.dropped = dropped;
		}
	}

	/** What {@link #updateActive} decided for this frame. */
	static final class Activation {
		/** Page entries to simulate, {@code tile << 2 | layer}. */
		final int[] pages;
		/** Flux pages handed out this frame; they must be zeroed before the first solver step. */
		final int[] newFluxPages;

		Activation(int[] pages, int[] newFluxPages) {
			this.pages = pages;
			this.newFluxPages = newFluxPages;
		}
	}

	final int originX;
	final int originZ;
	final int yMin;
	final int yMax;
	final int cpb;
	final int layers;
	final float minOpening;
	final float cell;
	/** Columns along one tile side. */
	final int tn;
	final int pageSpans;
	final int maxTiles;
	final int maxPages;
	final int maxFluxPages;

	private final HashMap<Long, Integer> slots = new HashMap<>();
	final int[] tileX;
	final int[] tileZ;
	/** Neighbor tile per direction, {@code tile * 4 + dir}, or {@link #NONE}. */
	final int[] nbr;
	final int[] layersUsed;
	/** Span page per layer, {@code tile * 4 + layer}. */
	final int[] spanPage;
	/** Flux page per layer while the tile is simulated, else {@link #NONE}. */
	final int[] fluxPage;
	/** {@code tile << 2 | layer} for every span page in use, else {@link #NONE}. */
	final int[] pageOwner;
	private final ArrayDeque<Integer> freeSpan = new ArrayDeque<>();
	private final ArrayDeque<Integer> freeFlux = new ArrayDeque<>();
	int count;
	int droppedSpans;
	private final Set<Integer> dirty = new LinkedHashSet<>();
	/** CPU copy of each tile's packed geometry ({@code layer * pageSpans + col}), for block sync and placing water. */
	final int[][] tileGeo;
	/** Bumped on every rebuild, so a block sync computed from an older topology is thrown away. */
	final int[] version;

	// Reservoirs: sub-columns held at a water level (ocean or river edges of a captured area) or filled up to it (springs).
	static final int RESERVOIR_FIXED = 0;
	static final int RESERVOIR_SOURCE = 1;
	private final HashMap<Long, float[]> reservoirs = new HashMap<>();
	boolean reservoirsDirty;

	// Block sync scheduling (heightfield -> realistic water blocks).
	final float[] syncAge;
	final boolean[] syncForced;
	final boolean[] syncedAsleep;
	final boolean[] syncPending;

	// Per-tile stats, filled in by whoever ran the depth pass (GPU readback or the CPU test).
	final double[] volume;
	final float[] maxDepth;
	final float[] maxSpeed;
	final float[] maxRate;
	/** Bit d set: water at this tile's border in direction d, where no neighbor tile exists yet. */
	final int[] border;
	final float[] quiet;
	final boolean[] active;
	int[] activeList = new int[0];

	HfTileGrid(int originX, int originZ, int yMin, int yMax, int cpb, int layers, float minOpening, int maxTiles, int maxPages, int maxFluxPages) {
		if (layers < 1 || layers > MAX_LAYERS) {
			throw new IllegalArgumentException("layers must be 1.." + MAX_LAYERS);
		}
		if ((yMax - yMin) * cpb >= OPEN_U) {
			throw new IllegalArgumentException("vertical range too large for 16-bit spans");
		}
		this.originX = originX;
		this.originZ = originZ;
		this.yMin = yMin;
		this.yMax = yMax;
		this.cpb = cpb;
		this.layers = layers;
		this.minOpening = minOpening;
		this.cell = 1.0F / cpb;
		this.tn = TILE_BLOCKS * cpb;
		this.pageSpans = this.tn * this.tn;
		this.maxTiles = maxTiles;
		this.maxPages = maxPages;
		this.maxFluxPages = maxFluxPages;
		this.tileX = new int[maxTiles];
		this.tileZ = new int[maxTiles];
		this.nbr = new int[maxTiles * 4];
		this.layersUsed = new int[maxTiles];
		this.spanPage = new int[maxTiles * 4];
		this.fluxPage = new int[maxTiles * 4];
		this.pageOwner = new int[maxPages];
		Arrays.fill(this.nbr, NONE);
		Arrays.fill(this.spanPage, NONE);
		Arrays.fill(this.fluxPage, NONE);
		Arrays.fill(this.pageOwner, NONE);
		for (int p = 0; p < maxPages; p++) {
			this.freeSpan.add(p);
		}
		for (int p = 0; p < maxFluxPages; p++) {
			this.freeFlux.add(p);
		}
		this.volume = new double[maxTiles];
		this.maxDepth = new float[maxTiles];
		this.maxSpeed = new float[maxTiles];
		this.maxRate = new float[maxTiles];
		this.border = new int[maxTiles];
		this.quiet = new float[maxTiles];
		this.active = new boolean[maxTiles];
		this.tileGeo = new int[maxTiles][];
		this.version = new int[maxTiles];
		this.syncAge = new float[maxTiles];
		this.syncForced = new boolean[maxTiles];
		this.syncedAsleep = new boolean[maxTiles];
		this.syncPending = new boolean[maxTiles];
	}

	// ---- packing ----

	int packGeo(float floor, float ceil) {
		if (!(ceil > floor)) {
			return INVALID_GEO;
		}
		int f = Math.round((floor - this.yMin) * this.cpb);
		int c = ceil >= HfTopology.OPEN_CEIL ? OPEN_U : Math.round((ceil - this.yMin) * this.cpb);
		return f | c << 16;
	}

	/** Same arithmetic as the shaders, so CPU and GPU agree bit for bit. */
	float floorOf(int geo) {
		return this.yMin + (geo & 0xFFFF) / (float) this.cpb;
	}

	float ceilOf(int geo) {
		int c = geo >>> 16;
		return c == OPEN_U ? HfTopology.OPEN_CEIL : this.yMin + c / (float) this.cpb;
	}

	static boolean validGeo(int geo) {
		return (geo >>> 16) > (geo & 0xFFFF);
	}

	static int link(int packed, int k) {
		return packed >>> (k * 4) & 0xF;
	}

	// ---- tiles ----

	private static long key(int tx, int tz) {
		return ((long) tx << 32) ^ (tz & 0xFFFFFFFFL);
	}

	int slotAt(int tx, int tz) {
		Integer s = this.slots.get(key(tx, tz));
		return s != null ? s : NONE;
	}

	int tileOfBlockX(int bx) {
		return Math.floorDiv(bx - this.originX, TILE_BLOCKS);
	}

	int tileOfBlockZ(int bz) {
		return Math.floorDiv(bz - this.originZ, TILE_BLOCKS);
	}

	/** Span index of (tile, layer, col), or {@link #NONE} when the tile has no page for that layer. */
	int span(int tile, int layer, int col) {
		int p = this.spanPage[tile * 4 + layer];
		return p == NONE ? NONE : p * this.pageSpans + col;
	}

	int freeSpanPages() {
		return this.freeSpan.size();
	}

	int freeFluxPages() {
		return this.freeFlux.size();
	}

	/**
	 * Builds a tile's spans and links. The topology is built over the tile plus a one-block halo, so links across the tile border
	 * come out exactly as the neighbor tile computes them from its side (both depend only on the two columns).
	 */
	Tile build(HfTopology.Voxels voxels, int tx, int tz) {
		int bx = this.originX + tx * TILE_BLOCKS - 1;
		int bz = this.originZ + tz * TILE_BLOCKS - 1;
		int size = TILE_BLOCKS + 2;
		HfTopology t = HfTopology.build(voxels, bx, bz, size, size, this.yMin, this.yMax, this.cpb, this.layers, this.minOpening);
		int ps = this.pageSpans;
		int used = 0;
		int dropped = 0;
		int[] geo = new int[this.layers * ps];
		int[] links = new int[this.layers * ps];
		for (int lz = 0; lz < this.tn; lz++) {
			for (int lx = 0; lx < this.tn; lx++) {
				int col = lz * this.tn + lx;
				for (int l = 0; l < this.layers; l++) {
					int a = t.span(lx + this.cpb, lz + this.cpb, l);
					int i = l * ps + col;
					geo[i] = this.packGeo(t.floor[a], t.ceil[a]);
					if (validGeo(geo[i])) {
						used = Math.max(used, l + 1);
					}
					int packed = 0;
					for (int k = 0; k < HfTopology.LINKS; k++) {
						int v = t.links[a * HfTopology.LINKS + k];
						int nib = v == HfTopology.NONE ? LINK_NONE : (v & 1) << 2 | (v >>> 3) % this.layers;
						packed |= nib << (k * 4);
					}
					links[i] = packed;
				}
			}
		}
		// HfTopology counts dropped spans over the whole halo build; the tile's own share is close enough for a diagnostic.
		dropped = (int) Math.round(t.droppedSpans * (double) (this.tn * this.tn) / (t.nx * t.nz));
		return new Tile(Math.max(used, 1), geo, links, dropped);
	}

	private int[] takeSpanPages(int tile, int n) {
		if (this.freeSpan.size() < n) {
			return null;
		}
		int[] pages = new int[MAX_LAYERS];
		Arrays.fill(pages, NONE);
		for (int l = 0; l < n; l++) {
			int p = this.freeSpan.poll();
			pages[l] = p;
			this.pageOwner[p] = tile << 2 | l;
		}
		return pages;
	}

	/** Reserves a tile slot and span pages for tile (tx, tz) and wires it to its neighbors. Returns the tile, or {@link #NONE} if full. */
	int add(int tx, int tz, Tile t) {
		if (this.count >= this.maxTiles || this.slotAt(tx, tz) != NONE || this.freeSpan.size() < t.layersUsed) {
			return NONE;
		}
		int s = this.count++;
		int[] pages = this.takeSpanPages(s, t.layersUsed);
		System.arraycopy(pages, 0, this.spanPage, s * 4, MAX_LAYERS);
		this.layersUsed[s] = t.layersUsed;
		this.tileGeo[s] = t.geo;
		this.slots.put(key(tx, tz), s);
		this.tileX[s] = tx;
		this.tileZ[s] = tz;
		for (int d = 0; d < 4; d++) {
			int n = this.slotAt(tx + DX[d], tz + DZ[d]);
			this.nbr[s * 4 + d] = n;
			if (n != NONE) {
				this.nbr[n * 4 + (d ^ 1)] = s;
			}
		}
		this.quiet[s] = 0.0F;
		this.droppedSpans += t.dropped;
		return s;
	}

	/**
	 * Gives a tile new span pages for a rebuilt topology. The old pages stay owned until {@link #releaseSpanPages}, so the water
	 * can be remapped from them first. The tile drops out of the simulation (its flux pages are freed) and is woken, so the next
	 * {@link #updateActive} gives it fresh, zeroed flux pages matching the new layer count.
	 *
	 * @return the old span pages, or null if there are not enough free pages (the tile then keeps its old topology)
	 */
	int[] replace(int tile, Tile t) {
		if (this.freeSpan.size() < t.layersUsed) {
			return null;
		}
		int[] old = Arrays.copyOfRange(this.spanPage, tile * 4, tile * 4 + 4);
		for (int p : old) {
			if (p != NONE) {
				// Still holds the old water for the remap, but no longer part of the tile (the mesh must not draw it).
				this.pageOwner[p] = NONE;
			}
		}
		int[] pages = this.takeSpanPages(tile, t.layersUsed);
		System.arraycopy(pages, 0, this.spanPage, tile * 4, MAX_LAYERS);
		this.layersUsed[tile] = t.layersUsed;
		this.tileGeo[tile] = t.geo;
		this.version[tile]++;
		// A simulated tile keeps its flux pages through the rebuild (the remap moves each column's flow along with its water), so
		// breaking a block does not stop the flow of the whole tile while its neighbors keep pushing, which made the water
		// surge. Only when the tile now needs a layer it holds no flux page for does it start over (fluxes zeroed).
		boolean keep = this.active[tile];
		for (int l = 0; l < t.layersUsed && keep; l++) {
			keep = this.fluxPage[tile * 4 + l] != NONE;
		}
		if (keep) {
			for (int l = t.layersUsed; l < MAX_LAYERS; l++) {
				int p = this.fluxPage[tile * 4 + l];
				if (p != NONE) {
					this.freeFlux.add(p);
					this.fluxPage[tile * 4 + l] = NONE;
				}
			}
		} else {
			this.releaseFlux(tile);
			this.active[tile] = false;
		}
		this.quiet[tile] = 0.0F;
		return old;
	}

	void releaseSpanPages(int[] pages) {
		for (int p : pages) {
			if (p != NONE) {
				this.freeSpan.add(p);
			}
		}
	}

	private void releaseFlux(int tile) {
		for (int l = 0; l < MAX_LAYERS; l++) {
			int p = this.fluxPage[tile * 4 + l];
			if (p != NONE) {
				this.freeFlux.add(p);
				this.fluxPage[tile * 4 + l] = NONE;
			}
		}
	}

	// ---- edits ----

	/** A block changed: dirties the tile containing it, and the axis neighbor whose one-block halo also contains it. */
	void markDirtyBlock(int bx, int by, int bz) {
		if (by < this.yMin - 1 || by > this.yMax) {
			return;
		}
		int tx = this.tileOfBlockX(bx);
		int tz = this.tileOfBlockZ(bz);
		int lx = bx - this.originX - tx * TILE_BLOCKS;
		int lz = bz - this.originZ - tz * TILE_BLOCKS;
		this.markDirtyTile(tx, tz);
		if (lx == 0) {
			this.markDirtyTile(tx - 1, tz);
		} else if (lx == TILE_BLOCKS - 1) {
			this.markDirtyTile(tx + 1, tz);
		}
		if (lz == 0) {
			this.markDirtyTile(tx, tz - 1);
		} else if (lz == TILE_BLOCKS - 1) {
			this.markDirtyTile(tx, tz + 1);
		}
	}

	/** A chunk (re)loaded: the tile on it and its four neighbors (whose halos reach into it) are rebuilt from fresh terrain. */
	void markDirtyChunk(int chunkX, int chunkZ) {
		int tx = this.tileOfBlockX(chunkX * 16);
		int tz = this.tileOfBlockZ(chunkZ * 16);
		this.markDirtyTile(tx, tz);
		for (int d = 0; d < 4; d++) {
			this.markDirtyTile(tx + DX[d], tz + DZ[d]);
		}
	}

	private void markDirtyTile(int tx, int tz) {
		int s = this.slotAt(tx, tz);
		if (s != NONE) {
			this.dirty.add(s);
		}
	}

	/** All dirty tiles, cleared. They must be rebuilt in the same frame, so both sides of a tile border always agree. */
	int[] takeDirty() {
		int[] out = this.dirty.stream().mapToInt(Integer::intValue).toArray();
		this.dirty.clear();
		return out;
	}

	// ---- per frame ----

	/** Missing tiles that water is pushing into, at most {@code max}, as {tx, tz} pairs. */
	int[][] growthRequests(int max) {
		if (this.count >= this.maxTiles) {
			return new int[0][];
		}
		int room = Math.min(max, this.maxTiles - this.count);
		int[][] out = new int[room][];
		int n = 0;
		HashMap<Long, Boolean> seen = new HashMap<>();
		for (int s = 0; s < this.count && n < room; s++) {
			if (this.border[s] == 0) {
				continue;
			}
			for (int d = 0; d < 4 && n < room; d++) {
				if ((this.border[s] & (1 << d)) == 0 || this.nbr[s * 4 + d] != NONE) {
					continue;
				}
				int tx = this.tileX[s] + DX[d];
				int tz = this.tileZ[s] + DZ[d];
				if (seen.put(key(tx, tz), Boolean.TRUE) == null) {
					out[n++] = new int[]{tx, tz};
				}
			}
		}
		return Arrays.copyOf(out, n);
	}

	/**
	 * Advances the per-tile quiet timers from the latest stats and picks this frame's dispatch set: every awake tile plus its
	 * neighbors (so water can flow into a sleeping tile and wake it). Links into tiles outside the set act as walls, which keeps the
	 * volume exact. Tiles leaving the set give back their flux pages; tiles entering it get fresh ones (to be zeroed).
	 *
	 * @param filmDepth tiles whose deepest water is below this only hold a thin film; they count as quiet below {@code filmRate}
	 *                  instead of {@code sleepRate}, so a film creeping across flat ground does not keep the solver awake forever
	 */
	Activation updateActive(float frameDt, float sleepRate, float sleepSeconds, float filmDepth, float filmRate) {
		boolean[] awake = new boolean[this.count];
		for (int s = 0; s < this.count; s++) {
			float limit = this.maxDepth[s] < filmDepth ? Math.max(sleepRate, filmRate) : sleepRate;
			if (this.maxRate[s] < limit) {
				this.quiet[s] += frameDt;
			} else {
				this.quiet[s] = 0.0F;
			}
			awake[s] = this.quiet[s] < sleepSeconds;
			if (awake[s]) {
				this.syncedAsleep[s] = false;
			}
		}
		boolean[] want = new boolean[this.count];
		for (int s = 0; s < this.count; s++) {
			if (awake[s]) {
				want[s] = true;
				for (int d = 0; d < 4; d++) {
					int n = this.nbr[s * 4 + d];
					if (n != NONE) {
						want[n] = true;
					}
				}
			}
		}
		for (int s = 0; s < this.count; s++) {
			if (this.active[s] && !want[s]) {
				this.releaseFlux(s);
				this.active[s] = false;
			}
		}
		// Awake tiles get flux pages first, their sleeping neighbors after; a tile that finds no room stays out (a wall) this frame.
		int[] fresh = new int[this.maxFluxPages];
		int nFresh = 0;
		for (int pass = 0; pass < 2; pass++) {
			for (int s = 0; s < this.count; s++) {
				if (!want[s] || this.active[s] || awake[s] != (pass == 0) || this.freeFlux.size() < this.layersUsed[s]) {
					continue;
				}
				for (int l = 0; l < this.layersUsed[s]; l++) {
					int p = this.freeFlux.poll();
					this.fluxPage[s * 4 + l] = p;
					fresh[nFresh++] = p;
				}
				this.active[s] = true;
			}
		}
		int nTiles = 0;
		int nPages = 0;
		int[] tiles = new int[this.count];
		int[] pages = new int[this.count * MAX_LAYERS];
		for (int s = 0; s < this.count; s++) {
			if (this.active[s]) {
				tiles[nTiles++] = s;
				for (int l = 0; l < this.layersUsed[s]; l++) {
					pages[nPages++] = s << 2 | l;
				}
			}
		}
		this.activeList = Arrays.copyOf(tiles, nTiles);
		return new Activation(Arrays.copyOf(pages, nPages), Arrays.copyOf(fresh, nFresh));
	}

	// ---- columns, reservoirs, block sync ----

	/** Tile and column of world point (x, z) as {tile, col}, or null when no tile covers it. */
	int[] locate(double x, double z) {
		int tx = this.tileOfBlockX((int) Math.floor(x));
		int tz = this.tileOfBlockZ((int) Math.floor(z));
		int tile = this.slotAt(tx, tz);
		if (tile == NONE) {
			return null;
		}
		int lx = Math.clamp((int) Math.floor((x - this.originX - tx * (double) TILE_BLOCKS) * this.cpb), 0, this.tn - 1);
		int lz = Math.clamp((int) Math.floor((z - this.originZ - tz * (double) TILE_BLOCKS) * this.cpb), 0, this.tn - 1);
		return new int[]{tile, lz * this.tn + lx};
	}

	/** Layer of the span in column {@code col} whose open space contains height y, or {@link #NONE}. */
	int layerAt(int tile, int col, float y) {
		int[] geo = this.tileGeo[tile];
		for (int l = 0; l < this.layersUsed[tile]; l++) {
			int g = geo[l * this.pageSpans + col];
			if (validGeo(g) && this.floorOf(g) <= y && y < this.ceilOf(g)) {
				return l;
			}
		}
		return NONE;
	}

	private static long reservoirKey(int tile, int col) {
		return (long) tile << 32 | col;
	}

	void setReservoir(int tile, int col, float level, int mode) {
		this.reservoirs.put(reservoirKey(tile, col), new float[]{tile, col, level, mode});
		this.reservoirsDirty = true;
	}

	void removeReservoir(int tile, int col) {
		if (this.reservoirs.remove(reservoirKey(tile, col)) != null) {
			this.reservoirsDirty = true;
		}
	}

	/** Reservoir entries as {tile, col, level, mode}. */
	java.util.Collection<float[]> reservoirs() {
		return this.reservoirs.values();
	}

	/**
	 * Picks tiles whose water should be written back to blocks this frame: forced ones first, then tiles that just fell asleep,
	 * then awake tiles not synced for {@code interval} seconds (oldest first). They stay pending until {@link #synced}.
	 */
	int[] pickSync(float frameDt, float interval, float sleepSeconds, int max) {
		int[] out = new int[Math.min(max, this.count)];
		int n = 0;
		for (int s = 0; s < this.count; s++) {
			this.syncAge[s] += frameDt;
		}
		for (int pass = 0; pass < 3 && n < out.length; pass++) {
			for (int s = 0; s < this.count && n < out.length; s++) {
				if (this.syncPending[s]) {
					continue;
				}
				boolean asleep = this.quiet[s] >= sleepSeconds;
				boolean pick = switch (pass) {
					case 0 -> this.syncForced[s];
					case 1 -> asleep && !this.syncedAsleep[s];
					default -> !asleep && this.syncAge[s] >= interval;
				};
				if (pick) {
					this.syncPending[s] = true;
					out[n++] = s;
				}
			}
		}
		return Arrays.copyOf(out, n);
	}

	/** A tile's block sync finished; {@code asleep}: whether it was asleep then (so it is not synced again until it wakes). */
	void synced(int tile, boolean asleep) {
		this.syncPending[tile] = false;
		this.syncForced[tile] = false;
		this.syncAge[tile] = 0.0F;
		this.syncedAsleep[tile] = asleep;
	}

	int awakeCount(float sleepSeconds) {
		int n = 0;
		for (int s = 0; s < this.count; s++) {
			if (this.quiet[s] < sleepSeconds) {
				n++;
			}
		}
		return n;
	}

	double totalVolume() {
		double v = 0.0;
		for (int s = 0; s < this.count; s++) {
			v += this.volume[s];
		}
		return v;
	}

	/** Largest value over the tiles in the dispatch set, for the CFL time step. */
	float activeMax(float[] values) {
		float m = 0.0F;
		for (int s : this.activeList) {
			m = Math.max(m, values[s]);
		}
		return m;
	}

	int usedSpanPages() {
		return this.maxPages - this.freeSpan.size();
	}

	int usedFluxPages() {
		return this.maxFluxPages - this.freeFlux.size();
	}
}
