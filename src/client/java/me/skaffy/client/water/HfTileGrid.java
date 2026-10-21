package me.skaffy.client.water;

import java.util.Arrays;
import java.util.HashMap;

/**
 * CPU side of the sparse heightfield (milestone 3, docs/HEIGHTFIELD_DESIGN.md section 12): which 16x16-block tiles exist,
 * which GPU pool slot each one lives in, who neighbors whom, and which tiles are awake, simulated or asleep this frame.
 * Free of Minecraft and Vulkan types so the tests in tools/hf-cpu-test drive exactly the same bookkeeping as the game.
 *
 * <p>Span index inside the pool: {@code slot * tileSpans + (lz * tn + lx) * layers + layer}. A link is one byte,
 * {@code targetLayer << 3 | reverseSlot} ({@link #LINK_NONE} when absent); the target column is implied by the link slot's
 * direction (slot / 2: 0 = +x, 1 = -x, 2 = +z, 3 = -z), so links never have to be patched when a neighbor tile appears.
 * Eight link bytes per span are packed into two ints, slot k in int k / 4 at bits (k % 4) * 8.
 */
final class HfTileGrid {
	static final int TILE_BLOCKS = 16;
	static final int NONE = -1;
	static final int LINK_NONE = 0xFF;
	static final int[] DX = {1, -1, 0, 0};
	static final int[] DZ = {0, 0, 1, -1};

	/** Spans, links and initial water of one tile, ready to upload into a pool slot. */
	static final class Tile {
		final float[] floor;
		final float[] ceil;
		final int[] links;
		final int dropped;

		Tile(float[] floor, float[] ceil, int[] links, int dropped) {
			this.floor = floor;
			this.ceil = ceil;
			this.links = links;
			this.dropped = dropped;
		}

		boolean valid(int s) {
			return this.ceil[s] > this.floor[s];
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
	final int tileSpans;
	final int maxTiles;

	private final HashMap<Long, Integer> slots = new HashMap<>();
	final int[] tileX;
	final int[] tileZ;
	/** Neighbor slot per direction, {@code slot * 4 + dir}, or {@link #NONE}. */
	final int[] nbr;
	int count;
	int droppedSpans;

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

	HfTileGrid(int originX, int originZ, int yMin, int yMax, int cpb, int layers, float minOpening, int maxTiles) {
		this.originX = originX;
		this.originZ = originZ;
		this.yMin = yMin;
		this.yMax = yMax;
		this.cpb = cpb;
		this.layers = layers;
		this.minOpening = minOpening;
		this.cell = 1.0F / cpb;
		this.tn = TILE_BLOCKS * cpb;
		this.tileSpans = this.tn * this.tn * layers;
		this.maxTiles = maxTiles;
		this.tileX = new int[maxTiles];
		this.tileZ = new int[maxTiles];
		this.nbr = new int[maxTiles * 4];
		Arrays.fill(this.nbr, NONE);
		this.volume = new double[maxTiles];
		this.maxDepth = new float[maxTiles];
		this.maxSpeed = new float[maxTiles];
		this.maxRate = new float[maxTiles];
		this.border = new int[maxTiles];
		this.quiet = new float[maxTiles];
		this.active = new boolean[maxTiles];
	}

	private static long key(int tx, int tz) {
		return ((long) tx << 32) ^ (tz & 0xFFFFFFFFL);
	}

	int slotAt(int tx, int tz) {
		Integer s = this.slots.get(key(tx, tz));
		return s != null ? s : NONE;
	}

	/** Tile containing world block (bx, bz). */
	int tileOfBlockX(int bx) {
		return Math.floorDiv(bx - this.originX, TILE_BLOCKS);
	}

	int tileOfBlockZ(int bz) {
		return Math.floorDiv(bz - this.originZ, TILE_BLOCKS);
	}

	int span(int slot, int lx, int lz, int layer) {
		return slot * this.tileSpans + (lz * this.tn + lx) * this.layers + layer;
	}

	static int link(int[] links, int s, int k) {
		return (links[s * 2 + (k >> 2)] >>> ((k & 3) * 8)) & 0xFF;
	}

	/**
	 * Builds a tile's spans and links. The topology is built over the tile plus a one-block halo, so links across the tile
	 * border come out exactly as the neighbor tile computes them from its side (both depend only on the two columns).
	 */
	Tile build(HfTopology.Voxels voxels, int tx, int tz) {
		int bx = this.originX + tx * TILE_BLOCKS - 1;
		int bz = this.originZ + tz * TILE_BLOCKS - 1;
		int size = TILE_BLOCKS + 2;
		HfTopology t = HfTopology.build(voxels, bx, bz, size, size, this.yMin, this.yMax, this.cpb, this.layers, this.minOpening);
		float[] floor = new float[this.tileSpans];
		float[] ceil = new float[this.tileSpans];
		int[] links = new int[this.tileSpans * 2];
		Arrays.fill(links, -1);
		int dropped = 0;
		for (int lz = 0; lz < this.tn; lz++) {
			for (int lx = 0; lx < this.tn; lx++) {
				for (int l = 0; l < this.layers; l++) {
					int a = t.span(lx + this.cpb, lz + this.cpb, l);
					int s = (lz * this.tn + lx) * this.layers + l;
					floor[s] = t.floor[a];
					ceil[s] = t.ceil[a];
					for (int k = 0; k < HfTopology.LINKS; k++) {
						int v = t.links[a * HfTopology.LINKS + k];
						int b = v == HfTopology.NONE ? LINK_NONE : ((v >>> 3) % this.layers) << 3 | (v & 7);
						int word = s * 2 + (k >> 2);
						int shift = (k & 3) * 8;
						links[word] = links[word] & ~(0xFF << shift) | b << shift;
					}
				}
			}
		}
		// Spans dropped by the layer cap, counted for the tile's own columns only (the halo belongs to the neighbors).
		// HfTopology counts them over the whole halo build; approximate by the inner share, it is a diagnostic only.
		dropped = (int) Math.round(t.droppedSpans * (double) (this.tn * this.tn) / (t.nx * t.nz));
		return new Tile(floor, ceil, links, dropped);
	}

	/** Reserves a pool slot for tile (tx, tz) and wires it to its existing neighbors. Returns the slot, or {@link #NONE} if the pool is full. */
	int add(int tx, int tz, int dropped) {
		if (this.count >= this.maxTiles || this.slotAt(tx, tz) != NONE) {
			return NONE;
		}
		int s = this.count++;
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
		this.droppedSpans += dropped;
		return s;
	}

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
	 * neighbors (so water can flow into a sleeping tile and wake it). Links into tiles outside the set act as walls, which keeps
	 * the volume exact. Returns the slots that just left the set; their fluxes must be zeroed before the next solver step.
	 */
	int[] updateActive(float frameDt, float sleepRate, float sleepSeconds) {
		return this.updateActive(frameDt, sleepRate, sleepSeconds, 0.0F, sleepRate);
	}

	/**
	 * @param filmDepth tiles whose deepest water is below this only hold a thin film; they count as quiet below {@code filmRate}
	 *                  instead of {@code sleepRate}, so a film creeping across flat ground does not keep the solver awake forever
	 */
	int[] updateActive(float frameDt, float sleepRate, float sleepSeconds, float filmDepth, float filmRate) {
		boolean[] next = new boolean[this.count];
		for (int s = 0; s < this.count; s++) {
			float limit = this.maxDepth[s] < filmDepth ? Math.max(sleepRate, filmRate) : sleepRate;
			if (this.maxRate[s] < limit) {
				this.quiet[s] += frameDt;
			} else {
				this.quiet[s] = 0.0F;
			}
			if (this.quiet[s] < sleepSeconds) {
				next[s] = true;
				for (int d = 0; d < 4; d++) {
					int n = this.nbr[s * 4 + d];
					if (n != NONE) {
						next[n] = true;
					}
				}
			}
		}
		int[] left = new int[this.count];
		int nLeft = 0;
		int[] list = new int[this.count];
		int nList = 0;
		for (int s = 0; s < this.count; s++) {
			if (this.active[s] && !next[s]) {
				left[nLeft++] = s;
			}
			this.active[s] = next[s];
			if (next[s]) {
				list[nList++] = s;
			}
		}
		this.activeList = Arrays.copyOf(list, nList);
		return Arrays.copyOf(left, nLeft);
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

	/** Largest depth and speed over the tiles in the dispatch set, for the CFL time step. */
	float activeMax(float[] values) {
		float m = 0.0F;
		for (int s : this.activeList) {
			m = Math.max(m, values[s]);
		}
		return m;
	}
}
