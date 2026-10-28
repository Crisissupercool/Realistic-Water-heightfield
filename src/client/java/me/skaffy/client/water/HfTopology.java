package me.skaffy.client.water;

import java.util.Arrays;

/**
 * Open spans per column and the links between spans of neighboring columns, built on the CPU from {@link SolidCache}.
 * See docs/HEIGHTFIELD_DESIGN.md, sections 1 and 4.
 *
 * <p>Span index: {@code (cz * nx + cx) * layers + layer}, layer 0 is the span nearest the sky.
 * Link slots per span: {@code dir * 2 + j}, dir 0 = +x, 1 = -x, 2 = +z, 3 = -z. A link is packed as
 * {@code target << 3 | reverseSlot}, or -1 when there is no link.
 */
final class HfTopology {
	static final int LINKS = 8;
	static final int NONE = -1;
	static final float OPEN_CEIL = 1.0e9F;
	private static final float INVALID_FLOOR = 1.0e30F;
	private static final float INVALID_CEIL = -1.0e30F;
	private static final int[] DX = {1, -1, 0, 0};
	private static final int[] DZ = {0, 0, 1, -1};

	final int nx;
	final int nz;
	final int layers;
	final int spans;
	final float[] floor;
	final float[] ceil;
	final int[] links;
	int droppedSpans;

	private HfTopology(int nx, int nz, int layers) {
		this.nx = nx;
		this.nz = nz;
		this.layers = layers;
		this.spans = nx * nz * layers;
		this.floor = new float[this.spans];
		this.ceil = new float[this.spans];
		this.links = new int[this.spans * LINKS];
		Arrays.fill(this.floor, INVALID_FLOOR);
		Arrays.fill(this.ceil, INVALID_CEIL);
		Arrays.fill(this.links, NONE);
	}

	int span(int cx, int cz, int layer) {
		return (cz * this.nx + cx) * this.layers + layer;
	}

	boolean valid(int s) {
		return this.ceil[s] > this.floor[s];
	}

	/** Solid sub-voxels of one block, same bit layout as {@link SolidCache#blockVoxels}. Kept free of Minecraft types so the builder can be unit tested. */
	interface Voxels {
		long blockVoxels(int bx, int by, int bz);

		/**
		 * The lowest block y from which the column at (bx, bz) is all open (nothing solid, no fluid) up to the sky, or
		 * {@link Integer#MAX_VALUE} if not known. The build skips everything above it: with the window reaching the build limit
		 * most of a column is sky.
		 */
		default int top(int bx, int bz) {
			return Integer.MAX_VALUE;
		}

		/**
		 * Whether the open block at (bx, by, bz) is foliage the water passes through (leaves): water falling into it rains on through
		 * it as drops (HfTileGrid.Tile#foliage).
		 */
		default boolean foliage(int bx, int by, int bz) {
			return false;
		}
	}

	/**
	 * @param ox      world block x of the domain's -x edge
	 * @param oz      world block z of the domain's -z edge
	 * @param sizeX   domain size in blocks along x
	 * @param sizeZ   domain size in blocks along z
	 * @param yMin    lowest block row scanned; everything below counts as solid
	 * @param yMax    first block row above the scanned window; a span reaching it is open to the sky
	 * @param cpb     cells per block (sub-voxel resolution of {@code solids}, at most 4)
	 */
	static HfTopology build(Voxels solids, int ox, int oz, int sizeX, int sizeZ, int yMin, int yMax, int cpb, int layers, float minOpening) {
		HfTopology t = new HfTopology(sizeX * cpb, sizeZ * cpb, layers);
		int blocksY = yMax - yMin;
		int rows = blocksY * cpb;
		long[] masks = new long[blocksY];
		for (int iz = 0; iz < sizeZ; iz++) {
			for (int ix = 0; ix < sizeX; ix++) {
				int top = solids.top(ox + ix, oz + iz);
				int openFrom = top == Integer.MAX_VALUE ? blocksY : Math.clamp((long) top - yMin, 0, blocksY);
				for (int y = 0; y < openFrom; y++) {
					masks[y] = solids.blockVoxels(ox + ix, yMin + y, oz + iz);
				}
				for (int y = openFrom; y < blocksY; y++) {
					masks[y] = 0L;
				}
				for (int k = 0; k < cpb; k++) {
					for (int c = 0; c < cpb; c++) {
						t.scanColumn(ix * cpb + c, iz * cpb + k, masks, rows, openFrom * cpb, cpb, c, k, yMin, minOpening);
					}
				}
			}
		}
		t.buildLinks(minOpening);
		return t;
	}

	private static boolean solid(long[] masks, int row, int cpb, int c, int k) {
		int block = row / cpb;
		int j = row % cpb;
		int bit = c + cpb * (j + cpb * k);
		return ((masks[block] >>> bit) & 1L) != 0L;
	}

	/** openRows: rows from here up are known open (see {@link Voxels#top}); the scan starts below them, already in an open run. */
	private void scanColumn(int cx, int cz, long[] masks, int rows, int openRows, int cpb, int c, int k, int yMin, float minOpening) {
		int layer = 0;
		int runTop = -1;
		boolean topOpen = false;
		if (openRows < rows) {
			runTop = rows;
			topOpen = true;
		}
		for (int v = Math.min(rows, openRows) - 1; v >= -1; v--) {
			boolean isSolid = v < 0 || solid(masks, v, cpb, c, k);
			if (!isSolid) {
				if (runTop < 0) {
					runTop = v + 1;
					topOpen = v == rows - 1;
				}
				continue;
			}
			if (runTop < 0) {
				continue;
			}
			float fl = yMin + (v + 1) / (float) cpb;
			float ce = topOpen ? OPEN_CEIL : yMin + runTop / (float) cpb;
			runTop = -1;
			if (ce - fl < minOpening) {
				continue;
			}
			if (layer >= this.layers) {
				this.droppedSpans++;
				continue;
			}
			int s = this.span(cx, cz, layer++);
			this.floor[s] = fl;
			this.ceil[s] = ce;
		}
	}

	private float opening(int a, int b) {
		return Math.min(this.ceil[a], this.ceil[b]) - Math.max(this.floor[a], this.floor[b]);
	}

	/** Keeps the 2 largest openings per direction, then drops every link that is not mutual, so inflow always has a matching outflow. */
	private void buildLinks(float minOpening) {
		int[] cand = new int[this.spans * LINKS];
		Arrays.fill(cand, NONE);
		for (int cz = 0; cz < this.nz; cz++) {
			for (int cx = 0; cx < this.nx; cx++) {
				for (int d = 0; d < 4; d++) {
					int ncx = cx + DX[d];
					int ncz = cz + DZ[d];
					if (ncx < 0 || ncz < 0 || ncx >= this.nx || ncz >= this.nz) {
						continue;
					}
					for (int l = 0; l < this.layers; l++) {
						int a = this.span(cx, cz, l);
						if (!this.valid(a)) {
							continue;
						}
						int best0 = NONE;
						int best1 = NONE;
						float open0 = minOpening;
						float open1 = minOpening;
						for (int m = 0; m < this.layers; m++) {
							int b = this.span(ncx, ncz, m);
							if (!this.valid(b)) {
								continue;
							}
							float open = this.opening(a, b);
							if (open < minOpening) {
								continue;
							}
							if (best0 == NONE || open > open0) {
								best1 = best0;
								open1 = open0;
								best0 = b;
								open0 = open;
							} else if (best1 == NONE || open > open1) {
								best1 = b;
								open1 = open;
							}
						}
						cand[a * LINKS + d * 2] = best0;
						cand[a * LINKS + d * 2 + 1] = best1;
					}
				}
			}
		}
		for (int a = 0; a < this.spans; a++) {
			for (int slot = 0; slot < LINKS; slot++) {
				int b = cand[a * LINKS + slot];
				if (b == NONE) {
					continue;
				}
				int back = (slot / 2) ^ 1;
				for (int j = 0; j < 2; j++) {
					int rev = back * 2 + j;
					if (cand[b * LINKS + rev] == a) {
						this.links[a * LINKS + slot] = (b << 3) | rev;
						break;
					}
				}
			}
		}
	}
}
