package me.skaffy.client.water;

import java.util.function.IntPredicate;

/** CPU checks for HfTopology and a line-by-line Java mirror of hf_flux.comp / hf_depth.comp. Not part of the mod. */
public final class HfCpuTest {
	static final int C = 2;
	static final int L = 4;
	static int failures;
	/** Mirrors WaterSettings.hfMinFlowDepth: thinner water does not flow out (it stays as a film), so spreading stops. */
	static float MIN_FLOW = 0.01F;

	interface Solid {
		boolean at(int x, int y, int z);
	}

	static HfTopology.Voxels voxels(Solid solid) {
		return (bx, by, bz) -> solid.at(bx, by, bz) ? (1L << (C * C * C)) - 1L : 0L;
	}

	/** Water surface at block coordinates (x, z) of a single-layer tile at the origin. */
	static float surfaceAt(HfTiledSim sim, int tile, float x, float z) {
		int col = (int) (z * C) * sim.g.tn + (int) (x * C);
		return sim.g.floorOf(sim.geo[sim.g.span(tile, 0, col)]) + sim.depthAt(tile, 0, col);
	}

	/** Mirror of HfWorld.crater: a paraboloid crater of radius r and depth d at (x, z), its water piled into a ring r..2r. */
	static void crater(HfTiledSim sim, double x, double z, float surface, double r, double d) {
		HfTileGrid g = sim.g;
		float y = surface - 0.05F;
		float area = g.cell * g.cell;
		int cpb = g.cpb;
		java.util.List<float[]> takes = new java.util.ArrayList<>();
		java.util.List<float[]> gives = new java.util.ArrayList<>();
		double removed = 0.0;
		double weights = 0.0;
		for (int cz = (int) Math.floor((z - 2.0 * r) * cpb); cz <= (int) Math.ceil((z + 2.0 * r) * cpb); cz++) {
			for (int cx = (int) Math.floor((x - 2.0 * r) * cpb); cx <= (int) Math.ceil((x + 2.0 * r) * cpb); cx++) {
				double wx = (cx + 0.5) / cpb;
				double wz = (cz + 0.5) / cpb;
				double dist = Math.hypot(wx - x, wz - z);
				if (dist >= 2.0 * r) {
					continue;
				}
				int[] at = g.locate(wx, wz);
				if (at == null) {
					continue;
				}
				int layer = g.layerAt(at[0], at[1], y);
				if (layer == HfTileGrid.NONE) {
					continue;
				}
				float local = surface - g.floorOf(g.tileGeo[at[0]][layer * g.pageSpans + at[1]]);
				if (local <= 0.0F) {
					continue;
				}
				if (dist < r) {
					double take = Math.min(d * (1.0 - (dist / r) * (dist / r)), 0.8 * local);
					if (take > 0.0) {
						takes.add(new float[]{at[0], at[1], (float) take});
						removed += take * area;
					}
				} else {
					double w = Math.sin(Math.PI * (dist - r) / r);
					gives.add(new float[]{at[0], at[1], (float) w});
					weights += w;
				}
			}
		}
		if (removed <= 0.0 || weights <= 0.0) {
			return;
		}
		for (float[] t : takes) {
			sim.inject((int) t[0], (int) t[1], y, -t[2]);
		}
		for (float[] t : gives) {
			sim.inject((int) t[0], (int) t[1], y, (float) (removed * t[2] / weights / area));
		}
	}

	/** Sum of all outflows of a tile's simulated spans (blocks^3/s). */
	/** Highest water surface anywhere (spans holding more than 0.01 blocks). */
	static float maxSurface(HfTiledSim sim, HfTileGrid g) {
		float m = -1.0e9F;
		for (int t = 0; t < g.count; t++) {
			for (int l = 0; l < g.layersUsed[t]; l++) {
				for (int c = 0; c < g.pageSpans; c++) {
					int s = g.spanPage[t * 4 + l] * g.pageSpans + c;
					if (HfTileGrid.validGeo(sim.geo[s]) && sim.depth[s] > 0.01F) {
						m = Math.max(m, g.floorOf(sim.geo[s]) + sim.depth[s]);
					}
				}
			}
		}
		return m;
	}

	/**
	 * A lake the heightfield took over whose blocks are still vanilla water (as kelp, seagrass or water the capture could not
	 * convert stay), next to a shore with a log pillar; the third log is broken. keepOpen: HfWorld.voxels' rule (non-realistic
	 * fluid keeps the current topology's openness), else the old rule (it is solid). Returns {surface before, highest after, volume
	 * before, volume after}.
	 */
	static double[] foreignLake(boolean keepOpen) {
		boolean[] foreign = {false};
		boolean[] broken = {false};
		HfTileGrid grid = new HfTileGrid(0, 0, 40, 96, C, L, 0.25F, 4, 16, 16);
		long full = (1L << (C * C * C)) - 1L;
		HfTopology.Voxels vox = (x, y, z) -> {
			if (x == 3 && z == 8 && y >= 65 && y <= 70) {
				return broken[0] && y == 67 ? 0L : full;
			}
			if (x < 6 ? y < 65 : y < 59) {
				return full;
			}
			if (foreign[0] && x >= 6 && y < 65) {
				return keepOpen ? full & ~grid.openVoxels(x, y, z) : full;
			}
			return 0L;
		};
		HfTiledSim sim = new HfTiledSim(grid, vox);
		sim.maxNewTiles = 0;
		sim.addTile(0, 0, (l, c) -> (c % grid.tn) / C >= 6 && l == 0 ? 64.8 - 59.0 : 0.0);
		for (int f = 0; f < 60; f++) {
			sim.frame();
		}
		float before = maxSurface(sim, grid);
		double volume = sim.volume();
		foreign[0] = true;
		broken[0] = true;
		grid.markDirtyBlock(3, 67, 8);
		sim.frame();
		return new double[]{before, maxSurface(sim, grid), volume, sim.volume()};
	}

	/**
	 * Water flowing from tile 0 into tile 1; then tile 1 is rebuilt once. blind: tile 0's chunk is unloaded during that rebuild, so
	 * it reads as solid in tile 1's halo (what happened when chunks streamed back in). Returns {volume before, volume 2 s after}.
	 */
	static double[] blindRebuild(boolean blind) {
		boolean[] unloaded = {false};
		HfTileGrid grid = new HfTileGrid(0, 0, 40, 96, C, L, 0.25F, 4, 16, 16);
		long full = (1L << (C * C * C)) - 1L;
		HfTopology.Voxels vox = (x, y, z) -> unloaded[0] && x < 16 || y < 59 ? full : 0L;
		HfTiledSim sim = new HfTiledSim(grid, vox);
		sim.maxNewTiles = 0;
		sim.addTile(0, 0, (l, c) -> l == 0 ? 3.0 : 0.0);
		int t1 = sim.addTile(1, 0, (l, c) -> l == 0 ? 0.2 : 0.0);
		for (int f = 0; f < 30; f++) {
			sim.frame();
		}
		double before = sim.volume();
		unloaded[0] = blind;
		grid.redirty(t1);
		sim.frame();
		unloaded[0] = false;
		for (int f = 0; f < 120; f++) {
			sim.frame();
		}
		return new double[]{before, sim.volume()};
	}

	/**
	 * A pool on a plateau (floor 40) next to ground 30 blocks lower; the water pours over the edge. Returns {seconds until water
	 * first lands below, volume at the start, lowest volume seen, highest, end volume, most in the air}.
	 */
	static double[] cliffArrival() {
		HfTileGrid grid = new HfTileGrid(0, 0, 0, 96, C, L, 0.25F, 4, 16, 16);
		long full = (1L << (C * C * C)) - 1L;
		HfTopology.Voxels vox = (x, y, z) -> (x < 6 ? y < 40 : y < 10) ? full : 0L;
		HfTiledSim sim = new HfTiledSim(grid, vox);
		sim.maxNewTiles = 0;
		sim.addTile(0, 0, (l, c) -> l == 0 && (c % grid.tn) / C < 5 ? 2.0 : 0.0);
		double v0 = sim.volume();
		double lo = v0;
		double hi = v0;
		double first = -1.0;
		double inAir = 0.0;
		for (int f = 0; f < 60 * 8; f++) {
			sim.frame();
			double v = sim.volume();
			lo = Math.min(lo, v);
			hi = Math.max(hi, v);
			double airNow = 0.0;
			for (int i = 0; i < sim.air.length; i += HfTiledSim.AIR_STRIDE) {
				airNow += sim.air[i];
			}
			inAir = Math.max(inAir, airNow);
			if (first < 0.0) {
				int page = grid.spanPage[0];
				for (int c = 0; c < grid.pageSpans; c++) {
					int sp = page * grid.pageSpans + c;
					if (HfTileGrid.validGeo(sim.geo[sp]) && grid.floorOf(sim.geo[sp]) < 20.0F && sim.depth[sp] > 0.01F) {
						first = (f + 1) / 60.0;
						break;
					}
				}
			}
		}
		return new double[]{first, v0, lo, hi, sim.volume(), inAir};
	}

	/**
	 * A stream of q blocks^3/s landing on one corner cell of a 2x2-block top one block above the ground: {edge cells (of 16)
	 * pouring off more than the drawn-sheet threshold, top cells (of 16) holding water}.
	 */
	static int[] trickleOnBlock(float q, float minFlow, float sheetMin) {
		float keepMin = MIN_FLOW;
		MIN_FLOW = minFlow;
		HfTileGrid g = new HfTileGrid(0, 0, 0, 96, C, L, 0.25F, 4, 16, 16);
		long full = (1L << (C * C * C)) - 1L;
		HfTopology.Voxels vox = (x, y, z) -> (x >= 6 && x < 8 && z >= 6 && z < 8 ? y < 12 : y < 11) ? full : 0L;
		HfTiledSim sim = new HfTiledSim(g, vox);
		sim.maxNewTiles = 0;
		int tn = g.tn;
		sim.addTile(0, 0, (l, c) -> 0.0);
		float cellA = g.cell * g.cell;
		double[] out = new double[g.pageSpans * 8];
		int frames = 60 * 20;
		for (int f = 0; f < frames; f++) {
			sim.inject(0, 12 * tn + 12, 12.5F, q / 60.0F / cellA);
			sim.frame();
			int base = g.fluxPage[0] * g.pageSpans * 8;
			if (f >= frames - 300) {
				for (int i = 0; i < out.length; i++) {
					out[i] += sim.flux[base + i] / 300.0;
				}
			}
		}
		int pouring = 0;
		int wet = 0;
		int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (int z = 12; z < 16; z++) {
			for (int x = 12; x < 16; x++) {
				int c = z * tn + x;
				if (sim.depth[g.spanPage[0] * g.pageSpans + c] > 0.008F) {
					wet++;
				}
				for (int d = 0; d < 4; d++) {
					int nx = x + dirs[d][0];
					int nz = z + dirs[d][1];
					if (nx >= 12 && nx < 16 && nz >= 12 && nz < 16) {
						continue;
					}
					if (out[c * 8 + d * 2] + out[c * 8 + d * 2 + 1] > sheetMin) {
						pouring++;
					}
				}
			}
		}
		MIN_FLOW = keepMin;
		return new int[]{pouring, wet};
	}

	/**
	 * A small stream (0.2 blocks^3/s, a few cm deep) running down 1-block terraces over 4 tiles in a row: {tile-frames in the last
	 * 60 s in which a tile whose water was running (an outflow above the drawn-film threshold) the last time it was simulated is
	 * not simulated, volume that reached the last tile}. An asleep tile does not wake its neighbors, so the water stopped at the next tile border, and once its
	 * neighbors slept too its films and sheets vanished until something woke it again.
	 */
	static double[] streamTiles() {
		HfTileGrid g = new HfTileGrid(0, 0, 0, 96, C, L, 0.25F, 8, 16, 16);
		long full = (1L << (C * C * C)) - 1L;
		HfTopology.Voxels vox = (x, y, z) -> y < 40 - Math.floorDiv(Math.max(x, 0), 3) ? full : 0L;
		HfTiledSim sim = new HfTiledSim(g, vox);
		sim.maxNewTiles = 0;
		int[] tiles = new int[4];
		for (int i = 0; i < 4; i++) {
			tiles[i] = sim.addTile(i, 0, (l, c) -> 0.0);
		}
		float cellA = g.cell * g.cell;
		int tn = g.tn;
		int bad = 0;
		boolean[] running = new boolean[4];
		for (int f = 0; f < 60 * 180; f++) {
			sim.inject(tiles[0], 16 * tn + 1, 41.0F, 0.2F / 60.0F / cellA);
			sim.frame();
			for (int i = 0; i < 4; i++) {
				int t = tiles[i];
				if (!g.active[t]) {
					// Not simulated: its films and sheets are not drawn and nothing flows in or out.
					bad += running[i] && f >= 60 * 120 ? 1 : 0;
					continue;
				}
				running[i] = false;
				int fp = g.fluxPage[t * 4] * g.pageSpans * 8;
				for (int k = 0; k < g.pageSpans * 8 && !running[i]; k += 8) {
					float o = 0.0F;
					for (int j = 0; j < 8; j++) {
						o += sim.flux[fp + k + j];
					}
					running[i] = o > HfMeshMirror.FLOW_DRAW;
				}
			}
		}
		return new double[]{bad, g.volume[tiles[3]]};
	}

	/**
	 * A small stream (q blocks^3/s over 2 blocks of width) down 1-block terraces: {switches between drawn and hidden per second
	 * over a tile's spans after each span's first, the most any one span switched in 20 s, spans drawn on average}.
	 */
	static double[] filmFlicker(float q, boolean filmSwitch) {
		HfTiledSim.filmSwitch = filmSwitch;
		HfTileGrid g = new HfTileGrid(0, 0, 0, 96, C, L, 0.25F, 8, 16, 16);
		long full = (1L << (C * C * C)) - 1L;
		HfTopology.Voxels vox = (x, y, z) -> y < 40 - Math.floorDiv(Math.max(x, 0), 3) ? full : 0L;
		HfTiledSim sim = new HfTiledSim(g, vox);
		sim.maxNewTiles = 0;
		int t0 = sim.addTile(0, 0, (l, c) -> 0.0);
		sim.addTile(1, 0, (l, c) -> 0.0);
		HfMeshMirror.g = g;
		HfMeshMirror.sim = sim;
		float cellA = g.cell * g.cell;
		int tn = g.tn;
		boolean[] prev = new boolean[g.pageSpans];
		int[] flips = new int[g.pageSpans];
		double shown = 0.0;
		int frames = 0;
		for (int f = 0; f < 60 * 60; f++) {
			for (int z = 14; z < 18; z++) {
				sim.inject(t0, z * tn + 1, 41.0F, q / 4.0F / 60.0F / cellA);
			}
			sim.frame();
			if (f < 60 * 40) {
				continue;
			}
			frames++;
			for (int c = 0; c < g.pageSpans; c++) {
				boolean w = HfMeshMirror.wet(new HfMeshMirror.S(t0, 0, c));
				if (f > 60 * 40 && w != prev[c]) {
					flips[c]++;
				}
				prev[c] = w;
				shown += w ? 1 : 0;
			}
		}
		HfTiledSim.filmSwitch = true;
		int total = 0;
		int worst = 0;
		for (int c = 0; c < g.pageSpans; c++) {
			// The first switch is the water arriving or leaving; every further one is popping in and out.
			total += Math.max(flips[c] - 1, 0);
			worst = Math.max(worst, flips[c]);
		}
		return new double[]{total / 20.0, worst, shown / frames};
	}

	static double totalFlux(HfTiledSim sim, HfTileGrid grid, int tile) {
		double sum = 0.0;
		for (int l = 0; l < grid.layersUsed[tile]; l++) {
			int fp = grid.fluxPage[tile * 4 + l];
			if (fp < 0) {
				continue;
			}
			for (int i = fp * grid.pageSpans * 8; i < (fp + 1) * grid.pageSpans * 8; i++) {
				sum += sim.flux[i];
			}
		}
		return sum;
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok   " : "  FAIL ") + what);
		if (!ok) {
			failures++;
		}
	}

	// ---- mirror of the shaders ----
	static final class Sim {
		final HfTopology t;
		final float[] h;
		final float[] flux;
		final float cell = 1.0F / C;
		float maxDepth;
		float maxSpeed;
		float maxRate;

		Sim(HfTopology t, float[] h) {
			this.t = t;
			this.h = h;
			this.flux = new float[t.spans * 8];
		}

		void fluxPass(float dt, float keep) {
			for (int s = 0; s < t.spans; s++) {
				int base = s * 8;
				float hA = h[s];
				if (!t.valid(s) || hA <= 0.0F) {
					for (int k = 0; k < 8; k++) {
						flux[base + k] = 0.0F;
					}
					continue;
				}
				float headA = t.floor[s] + hA;
				float filmCarry = Math.clamp(hA / MIN_FLOW, 0.0F, 1.0F);
				float[] out = new float[8];
				float total = 0.0F;
				for (int k = 0; k < 8; k++) {
					int l = t.links[base + k];
					if (l == -1) {
						continue;
					}
					int b = l >>> 3;
					float headB = t.floor[b] + h[b];
					float lo = Math.max(t.floor[s], t.floor[b]);
					float hi = Math.min(t.ceil[s], t.ceil[b]);
					float wetA = Math.clamp(headA, lo, hi) - lo;
					if (wetA <= 0.0F) {
						continue;
					}
					float wetB = Math.clamp(headB, lo, hi) - lo;
					float area = cell * Math.max(wetA, wetB);
					float drive = headA - headB;
					float carry = 1.0F;
					if (headB >= t.floor[s]) {
						float fa = t.floor[s];
						drive = fa + Math.max(hA - MIN_FLOW, 0.0F) - Math.max(t.floor[b] + Math.max(h[b] - MIN_FLOW, 0.0F), fa);
						carry = filmCarry;
					}
					float q = flux[base + k] * keep * carry + dt * 9.81F * area * drive / cell;
					out[k] = Math.max(q, 0.0F);
					total += out[k];
				}
				float volume = hA * cell * cell;
				float scale = total * dt > volume ? volume / (total * dt) : 1.0F;
				for (int k = 0; k < 8; k++) {
					flux[base + k] = out[k] * scale;
				}
			}
		}

		double depthPass(float dt) {
			double vol = 0.0;
			maxDepth = 0.0F;
			maxSpeed = 0.0F;
			maxRate = 0.0F;
			float[] next = h.clone();
			for (int s = 0; s < t.spans; s++) {
				if (!t.valid(s)) {
					continue;
				}
				int base = s * 8;
				float in = 0.0F;
				float out = 0.0F;
				float peak = 0.0F;
				for (int k = 0; k < 8; k++) {
					int l = t.links[base + k];
					if (l == -1) {
						continue;
					}
					float o = flux[base + k];
					float i = flux[(l >>> 3) * 8 + (l & 7)];
					out += o;
					in += i;
					peak = Math.max(peak, Math.max(o, i));
				}
				float nh = Math.max(h[s] + dt * (in - out) / (cell * cell), 0.0F);
				next[s] = nh;
				maxRate = Math.max(maxRate, Math.abs(nh - h[s]) / dt);
				vol += (double) nh * cell * cell;
				maxDepth = Math.max(maxDepth, nh);
				if (nh > 0.05F) {
					maxSpeed = Math.max(maxSpeed, Math.min(peak / (cell * nh), 30.0F));
				}
			}
			System.arraycopy(next, 0, h, 0, h.length);
			return vol;
		}

		double volume() {
			double v = 0.0;
			for (int s = 0; s < t.spans; s++) {
				if (t.valid(s)) {
					v += (double) h[s] * cell * cell;
				}
			}
			return v;
		}

		/** Runs like WaterWorld would: CFL substeps per 1/60 s frame. Returns false on NaN or blow-up. */
		boolean run(int frames) {
			maxDepth = 0.0F;
			for (float v : h) {
				maxDepth = Math.max(maxDepth, v);
			}
			for (int f = 0; f < frames; f++) {
				float frameDt = 1.0F / 60.0F;
				float wave = (float) Math.sqrt(9.81F * Math.max(maxDepth, 0.01F)) + maxSpeed;
				float stable = 0.3F * cell / wave;
				int steps = Math.clamp((int) Math.ceil(frameDt / stable), 1, 8);
				float dt = Math.min(frameDt / steps, stable);
				float keep = (float) Math.pow(0.995, dt * 60.0) / (1.0F + 0.2F * dt);
				for (int s = 0; s < steps; s++) {
					fluxPass(dt, keep);
					depthPass(dt);
				}
				for (float v : h) {
					if (Float.isNaN(v) || v > 1000.0F) {
						return false;
					}
				}
			}
			return true;
		}
	}

	static float[] flood(HfTopology t, int size, double cxB, double czB, double radius, float level, float refY) {
		float[] h = new float[t.spans];
		for (int cz = 0; cz < t.nz; cz++) {
			for (int cx = 0; cx < t.nx; cx++) {
				double x = (cx + 0.5) / C - cxB;
				double z = (cz + 0.5) / C - czB;
				if (x * x + z * z > radius * radius) {
					continue;
				}
				for (int l = 0; l < L; l++) {
					int s = t.span(cx, cz, l);
					if (t.valid(s) && t.floor[s] < level && t.ceil[s] > refY - 2.0F) {
						h[s] = Math.min(level, t.ceil[s]) - t.floor[s];
					}
				}
			}
		}
		return h;
	}

	static int dryCells(HfTopology t, float[] h, float below) {
		int dry = 0;
		for (int cz = 0; cz < t.nz; cz++) {
			for (int cx = 0; cx < t.nx; cx++) {
				int s = t.span(cx, cz, 0);
				if (t.valid(s) && h[s] < below) {
					dry++;
				}
			}
		}
		return dry;
	}

	static boolean linksSymmetric(HfTopology t) {
		for (int a = 0; a < t.spans; a++) {
			for (int k = 0; k < 8; k++) {
				int l = t.links[a * 8 + k];
				if (l == -1) {
					continue;
				}
				int b = l >>> 3;
				int rev = l & 7;
				int back = t.links[b * 8 + rev];
				if (back == -1 || (back >>> 3) != a || (back & 7) != k) {
					return false;
				}
			}
		}
		return true;
	}

	public static void main(String[] args) {
		System.out.println("Topology: two-storey house over a cave");
		{
			// ground solid up to y=63 (floor at 64), cave air 50..54, house: floor slab y=67, roof y=71
			Solid solid = (x, y, z) -> {
				if (y >= 50 && y <= 54) {
					return false;
				}
				if (y < 64) {
					return true;
				}
				boolean inHouse = x >= 2 && x <= 5 && z >= 2 && z <= 5;
				return inHouse && (y == 67 || y == 71);
			};
			HfTopology t = HfTopology.build(voxels(solid), 0, 0, 8, 8, 40, 80, C, L, 0.25F);
			int s0 = t.span(3 * C, 3 * C, 0);
			check(t.floor[s0] == 72.0F && t.ceil[s0] == HfTopology.OPEN_CEIL, "sky span on the roof: floor 72, open");
			check(t.floor[s0 + 1] == 68.0F && t.ceil[s0 + 1] == 71.0F, "upper floor 68..71, got " + t.floor[s0 + 1] + ".." + t.ceil[s0 + 1]);
			check(t.floor[s0 + 2] == 64.0F && t.ceil[s0 + 2] == 67.0F, "ground floor 64..67");
			check(t.floor[s0 + 3] == 50.0F && t.ceil[s0 + 3] == 55.0F, "cave 50..55");
			int out = t.span(0, 0, 0);
			check(t.floor[out] == 64.0F && t.ceil[out] == HfTopology.OPEN_CEIL, "outside: sky span on the ground");
			check(t.floor[out + 1] == 50.0F, "outside: cave below");
			check(linksSymmetric(t), "every link has a matching reverse link");
			check(t.droppedSpans == 0, "no spans dropped");
		}

		System.out.println("Topology: span cap");
		{
			// 6 separate air gaps in one column, layers = 4 -> 2 dropped per column
			Solid solid = (x, y, z) -> y % 3 != 0;
			HfTopology t = HfTopology.build(voxels(solid), 0, 0, 1, 1, 0, 18, C, L, 0.25F);
			check(t.droppedSpans == 2 * C * C, "dropped spans counted: " + t.droppedSpans);
		}

		System.out.println("Solver: dam break on flat ground (32x32 blocks, cylinder r=6, 2 blocks high), 20 s");
		{
			Solid solid = (x, y, z) -> y < 64;
			HfTopology t = HfTopology.build(voxels(solid), 0, 0, 32, 32, 40, 96, C, L, 0.25F);
			float[] h = flood(t, 32, 16, 16, 6, 66.0F, 64.0F);
			Sim sim = new Sim(t, h);
			double v0 = sim.volume();
			boolean stable = sim.run(1200);
			double v1 = sim.volume();
			double drift = Math.abs(v1 - v0) / v0 * 100.0;
			check(stable, "no NaN or blow-up");
			check(drift < 0.1, String.format("volume drift %.4f%% (< 0.1%%)  %.2f -> %.2f", drift, v0, v1));
			int edge = t.span(1, 1, 0);
			check(sim.h[edge] > 0.0F, "water reached the far corner of the domain");
			check(sim.maxDepth < 1.0F, "settled: max depth " + sim.maxDepth);
			check(dryCells(t, sim.h, 0.01F) == 0, "no dry holes in the settled pool");
		}

		System.out.println("Solver: settled water can sleep (surface rate below WaterSettings.hfSleepRate = 0.002 b/s)");
		for (float height : new float[]{0.6F, 2.0F, 3.0F}) {
			Solid solid = (x, y, z) -> y < 64;
			HfTopology t = HfTopology.build(voxels(solid), 0, 0, 32, 32, 40, 96, C, L, 0.25F);
			Sim sim = new Sim(t, flood(t, 32, 16, 16, 6, 64.0F + height, 64.0F));
			int asleepAt = -1;
			for (int frame = 0; frame < 120 * 60 && asleepAt < 0; frame += 60) {
				sim.run(60);
				if (sim.maxRate < 0.002F) {
					asleepAt = frame / 60 + 1;
				}
			}
			check(asleepAt > 0, String.format("column %.1f high: surface still after %d s (rate %.4f b/s)", height, asleepAt, sim.maxRate));
		}

		System.out.println("Solver: doorway into a closed room");
		{
			// room x,z in [10,20], walls 1 block thick up to y=67, roof slab at y=68, doorway at x=10, z=15, y=64..65
			Solid solid = (x, y, z) -> {
				if (y < 64) {
					return true;
				}
				boolean wallX = (x == 10 || x == 20) && z >= 10 && z <= 20;
				boolean wallZ = (z == 10 || z == 20) && x >= 10 && x <= 20;
				boolean roof = y == 68 && x >= 10 && x <= 20 && z >= 10 && z <= 20;
				boolean door = x == 10 && z == 15 && y <= 65;
				return y <= 68 && ((wallX || wallZ) && y <= 67 && !door || roof);
			};
			HfTopology t = HfTopology.build(voxels(solid), 0, 0, 32, 32, 40, 96, C, L, 0.25F);
			int inside = t.span(15 * C, 15 * C, 1);
			check(t.valid(inside) && t.floor[inside] == 64.0F && t.ceil[inside] == 68.0F, "room span 64..68 under the roof");
			check(linksSymmetric(t), "links symmetric");
			// flood the outside to 1.5 blocks deep, everywhere except the room
			float[] h = new float[t.spans];
			for (int cz = 0; cz < t.nz; cz++) {
				for (int cx = 0; cx < t.nx; cx++) {
					int s = t.span(cx, cz, 0);
					if (cx < 8 * C && t.valid(s) && t.floor[s] == 64.0F) {
						h[s] = 1.5F;
					}
				}
			}
			Sim sim = new Sim(t, h);
			double v0 = sim.volume();
			boolean stable = sim.run(1800);
			check(stable, "no NaN or blow-up");
			check(sim.h[inside] > 0.1F, "water flowed through the doorway into the room: depth " + sim.h[inside]);
			int roofTop = t.span(15 * C, 15 * C, 0);
			check(sim.h[roofTop] == 0.0F, "roof stays dry");
			double drift = Math.abs(sim.volume() - v0) / v0 * 100.0;
			check(drift < 0.1, String.format("volume drift %.4f%%", drift));
		}

		System.out.println("Solver: tunnel under a river stays dry");
		{
			// river channel at z 14..17 cut 2 blocks deep into ground (floor 62); tunnel at y 55..57 under it, no opening
			Solid solid = (x, y, z) -> {
				boolean channel = z >= 14 && z <= 17 && y >= 62;
				boolean tunnel = x >= 4 && x <= 27 && z == 15 && y >= 55 && y <= 57;
				return y < 64 && !channel && !tunnel;
			};
			HfTopology t = HfTopology.build(voxels(solid), 0, 0, 32, 32, 40, 96, C, L, 0.25F);
			float[] h = new float[t.spans];
			for (int cz = 0; cz < t.nz; cz++) {
				for (int cx = 0; cx < t.nx; cx++) {
					int s = t.span(cx, cz, 0);
					if (t.floor[s] == 62.0F) {
						h[s] = 1.5F;
					}
				}
			}
			Sim sim = new Sim(t, h);
			boolean stable = sim.run(600);
			int tunnel = t.span(16 * C, 15 * C, 1);
			check(stable, "no NaN or blow-up");
			check(t.valid(tunnel) && t.floor[tunnel] == 55.0F, "tunnel span found under the river");
			check(sim.h[tunnel] == 0.0F, "tunnel stays dry");
		}


		System.out.println("Tiles: identical to the dense solver (48x48 blocks = 3x3 tiles, room and dam break across tile borders)");
		{
			Solid solid = (x, y, z) -> {
				if (y < 64) {
					return true;
				}
				boolean wallX = (x == 10 || x == 20) && z >= 10 && z <= 20;
				boolean wallZ = (z == 10 || z == 20) && x >= 10 && x <= 20;
				boolean roof = y == 68 && x >= 10 && x <= 20 && z >= 10 && z <= 20;
				boolean door = x == 10 && z == 15 && y <= 65;
				return y <= 68 && ((wallX || wallZ) && y <= 67 && !door || roof);
			};
			HfTopology dense = HfTopology.build(voxels(solid), 0, 0, 48, 48, 40, 96, C, L, 0.25F);
			float[] h0 = flood(dense, 48, 32, 31, 7, 66.0F, 64.0F);
			for (int cz = 0; cz < dense.nz; cz++) {
				for (int cx = 0; cx < 8 * C; cx++) {
					int s = dense.span(cx, cz, 0);
					if (dense.valid(s) && dense.floor[s] == 64.0F) {
						h0[s] = 1.5F;
					}
				}
			}
			HfTileGrid grid = new HfTileGrid(0, 0, 40, 96, C, L, 0.25F, 16, 64, 64);
			HfTiledSim tiled = new HfTiledSim(grid, voxels(solid));
			tiled.sleepRate = -1.0F;
			tiled.filmRate = -1.0F;
			tiled.maxNewTiles = 0;
			int tn = grid.tn;
			for (int tz = 0; tz < 3; tz++) {
				for (int tx = 0; tx < 3; tx++) {
					int ftx = tx;
					int ftz = tz;
					tiled.addTile(tx, tz, (l, col) -> h0[dense.span(ftx * tn + col % tn, ftz * tn + col / tn, l)]);
				}
			}
			boolean topoSame = true;
			for (int cz = 0; cz < dense.nz; cz++) {
				for (int cx = 0; cx < dense.nx; cx++) {
					int tile = grid.slotAt(cx / tn, cz / tn);
					int col = (cz % tn) * tn + cx % tn;
					for (int l = 0; l < L; l++) {
						int a = dense.span(cx, cz, l);
						if (l >= grid.layersUsed[tile]) {
							topoSame &= !dense.valid(a);
							continue;
						}
						int b = grid.span(tile, l, col);
						int gb = tiled.geo[b];
						if (dense.valid(a) != HfTileGrid.validGeo(gb) || dense.valid(a) && (dense.floor[a] != grid.floorOf(gb) || dense.ceil[a] != grid.ceilOf(gb))) {
							topoSame = false;
						}
						for (int k = 0; k < 8 && dense.valid(a); k++) {
							int nx = cx + HfTileGrid.DX[k >> 1];
							int nz = cz + HfTileGrid.DZ[k >> 1];
							if (nx < 0 || nz < 0 || nx >= dense.nx || nz >= dense.nz) {
								continue;
							}
							int la = dense.links[a * 8 + k];
							int lb = HfTileGrid.link(tiled.links[b], k);
							if ((la == -1) != (lb == HfTileGrid.LINK_NONE) || la != -1 && ((la >>> 3) % L != (lb & 3) || (la & 1) != lb >> 2)) {
								topoSame = false;
							}
						}
					}
				}
			}
			check(topoSame, "spans and links match the dense build, including across tile borders");
			int pagesUsed = grid.usedSpanPages();
			check(pagesUsed < 9 * L, "span pages: " + pagesUsed + " for 9 tiles (dense layout would need " + 9 * L + ")");
			Sim sim = new Sim(dense, h0);
			double v0 = sim.volume();
			for (int f = 0; f < 600; f++) {
				sim.run(1);
				tiled.frame();
			}
			float maxDiff = 0.0F;
			for (int cz = 0; cz < dense.nz; cz++) {
				for (int cx = 0; cx < dense.nx; cx++) {
					int tile = grid.slotAt(cx / tn, cz / tn);
					int col = (cz % tn) * tn + cx % tn;
					for (int l = 0; l < L; l++) {
						int a = dense.span(cx, cz, l);
						if (dense.valid(a)) {
							maxDiff = Math.max(maxDiff, Math.abs(sim.h[a] - tiled.depthAt(tile, l, col)));
						}
					}
				}
			}
			check(maxDiff < 1.0e-5F, String.format("after 10 s every depth matches the dense solver (max difference %.2e)", maxDiff));
			check(Math.abs(tiled.volume() - v0) / v0 < 1.0e-5, String.format("volume %.3f -> %.3f", v0, tiled.volume()));
			float room = tiled.depthAt(grid.slotAt(0, 0), 1, 15 * C * tn + 15 * C);
			check(room > 0.1F, "water crossed the tile border at x=16 into the room: depth " + room);
		}

		System.out.println("Tiles: water grows into new tiles (starts as 1 tile, column next to its +x border)");
		{
			Solid solid = (x, y, z) -> y < 64;
			HfTileGrid grid = new HfTileGrid(0, 0, 40, 96, C, L, 0.25F, 64, 128, 128);
			HfTiledSim tiled = new HfTiledSim(grid, voxels(solid));
			int tn = grid.tn;
			tiled.addTile(0, 0, (l, col) -> {
				double x = (col % tn + 0.5) / C - 12.0;
				double z = (col / tn + 0.5) / C - 8.0;
				return l == 0 && x * x + z * z < 16.0 ? 2.0 : 0.0;
			});
			double v0 = tiled.volume();
			for (int f = 0; f < 1800; f++) {
				tiled.frame();
			}
			double drift = Math.abs(tiled.volume() - v0) / v0 * 100.0;
			check(grid.count > 1, "tiles allocated as the water spread: " + grid.count);
			check(tiled.depthAt(grid.slotAt(1, 0), 0, (tn / 2) * tn + 4) > 0.0F, "water flowed into the new +x tile");
			check(drift < 0.1, String.format("volume drift %.4f%%", drift));
			check(grid.usedSpanPages() == grid.count, "open ground uses one span page per tile: " + grid.usedSpanPages() + " pages, " + grid.count + " tiles");
		}

		System.out.println("Tiles: settled water sleeps tile by tile, volume stays exact (open flat ground, wall at x=20)");
		{
			Solid solid = (x, y, z) -> y < 64 || x == 20 && y < 66;
			HfTileGrid grid = new HfTileGrid(0, 0, 40, 96, C, L, 0.25F, 64, 128, 128);
			HfTiledSim tiled = new HfTiledSim(grid, voxels(solid));
			for (int tz = 0; tz < 3; tz++) {
				for (int tx = 0; tx < 3; tx++) {
					int ftx = tx;
					int ftz = tz;
					tiled.addTile(tx, tz, (l, col) -> {
						double x = ftx * 16 + (col % grid.tn + 0.5) / C - 10.0;
						double z = ftz * 16 + (col / grid.tn + 0.5) / C - 24.0;
						return l == 0 && x * x + z * z < 36.0 ? 3.0 : 0.0;
					});
				}
			}
			double v0 = tiled.volume();
			int asleepAt = -1;
			int peakFlux = 0;
			// On open flat ground the edge keeps creeping outward as a thin film for minutes; film tiles sleep at a relaxed rate.
			for (int f = 0; f < 400 * 60; f++) {
				tiled.frame();
				peakFlux = Math.max(peakFlux, grid.usedFluxPages());
				if (grid.activeList.length == 0) {
					asleepAt = f / 60;
					break;
				}
			}
			double drift = Math.abs(tiled.volume() - v0) / v0 * 100.0;
			check(asleepAt >= 0, "every tile asleep after " + asleepAt + " s (" + grid.count + " tiles)");
			check(drift < 0.1, String.format("volume drift %.4f%%", drift));
			check(tiled.frame() == 0, "no solver steps once asleep");
			check(grid.usedFluxPages() == 0, "asleep tiles hold no flux pages (peak was " + peakFlux + ")");
		}

		System.out.println("Thin film drains into a hole instead of stopping at its edge");
		{
			Solid solid = (x, y, z) -> y < 64 && !(x >= 14 && x < 16 && z >= 14 && z < 16 && y >= 63);
			HfTopology t = HfTopology.build(voxels(solid), 0, 0, 32, 32, 40, 96, C, L, 0.25F);
			float[] h = new float[t.spans];
			for (int cz = 0; cz < t.nz; cz++) {
				for (int cx = 0; cx < t.nx; cx++) {
					int s = t.span(cx, cz, 0);
					if (t.valid(s) && t.floor[s] == 64.0F) {
						h[s] = 0.02F;
					}
				}
			}
			Sim sim = new Sim(t, h);
			double v0 = sim.volume();
			sim.run(600);
			double inHole = 0.0;
			for (int cz = 14 * C; cz < 16 * C; cz++) {
				for (int cx = 14 * C; cx < 16 * C; cx++) {
					inHole += sim.h[t.span(cx, cz, 0)] * (1.0 / (C * C));
				}
			}
			int edge = t.span(13 * C + 1, 15 * C, 0);
			check(inHole > 0.01, String.format("water collected in the hole: %.3f blocks", inHole));
			check(sim.h[edge] < 0.02F, "the film next to the hole drained into it: " + sim.h[edge]);
			check(Math.abs(sim.volume() - v0) / v0 < 1.0e-4, "volume kept");
		}

		System.out.println("Block edits: breaking a dam lets the water through, placing blocks in water displaces it");
		{
			java.util.Set<Long> removed = new java.util.HashSet<>();
			java.util.Set<Long> placed = new java.util.HashSet<>();
			Solid base = (x, y, z) -> y < 64 || x == 16 && y < 68;
			Solid solid = (x, y, z) -> {
				long key = ((long) x << 40) ^ ((long) y << 20) ^ (z & 0xFFFFF);
				return placed.contains(key) || !removed.contains(key) && base.at(x, y, z);
			};
			HfTileGrid grid = new HfTileGrid(0, 0, 40, 96, C, L, 0.25F, 16, 64, 64);
			HfTiledSim tiled = new HfTiledSim(grid, voxels(solid));
			tiled.maxNewTiles = 0;
			int tn = grid.tn;
			// Two tiles side by side; the dam is the x=16 column, i.e. the first block row of tile (1, 0). Water 3 deep on the -x side.
			for (int tx = 0; tx < 2; tx++) {
				int ftx = tx;
				tiled.addTile(tx, 0, (l, col) -> ftx == 0 && l == 0 ? 3.0 : 0.0);
			}
			double v0 = tiled.volume();
			for (int f = 0; f < 300; f++) {
				tiled.frame();
			}
			float behind = tiled.depthAt(grid.slotAt(1, 0), 0, 8 * tn + 10);
			check(behind == 0.0F, "dam holds: no water behind it before the edit");
			for (int z = 4; z < 12; z++) {
				for (int y = 64; y < 68; y++) {
					long key = (16L << 40) ^ ((long) y << 20) ^ z;
					removed.add(key);
					grid.markDirtyBlock(16, y, z);
				}
			}
			check(grid.slotAt(0, 0) >= 0, "edit at the tile border marks both tiles dirty");
			for (int f = 0; f < 600; f++) {
				tiled.frame();
			}
			behind = tiled.depthAt(grid.slotAt(1, 0), 0, 8 * tn + 10);
			double drift = Math.abs(tiled.volume() - v0) / v0 * 100.0;
			check(behind > 0.3F, "after breaking an 8-block gap, water flows through: depth behind the dam " + behind);
			check(drift < 1.0e-3, String.format("volume drift %.6f%% through the rebuild", drift));
			// Fill a 4x4 block patch of the water with stone, 2 blocks high, at the bottom of the pool: the stone replaces the water in
			// those cells (as a placed block replaces a water block), and nothing is stood up on top of it.
			double v1 = tiled.volume();
			double inPatch = 0.0;
			float level = 0.0F;
			for (int cz = 4 * C; cz < 8 * C; cz++) {
				for (int cx = 4 * C; cx < 8 * C; cx++) {
					float d = tiled.depthAt(grid.slotAt(0, 0), 0, cz * tn + cx);
					inPatch += Math.min(d, 2.0F) * grid.cell * grid.cell;
					level = Math.max(level, 64.0F + d);
				}
			}
			for (int x = 4; x < 8; x++) {
				for (int z = 4; z < 8; z++) {
					for (int y = 64; y < 66; y++) {
						placed.add(((long) x << 40) ^ ((long) y << 20) ^ z);
						grid.markDirtyBlock(x, y, z);
					}
				}
			}
			tiled.frame();
			double v2 = tiled.volume();
			check(Math.abs(v1 - v2 - inPatch) < 1.0e-3, String.format("placing 32 blocks in the water removes exactly the water in them: %.4f -> %.4f (%.4f in the cells)", v1, v2, inPatch));
			int col = 5 * C * tn + 5 * C;
			float onTop = tiled.depthAt(grid.slotAt(0, 0), 0, col);
			check(grid.floorOf(tiled.geo[grid.span(grid.slotAt(0, 0), 0, col)]) == 66.0F, "the patch raised the floor to 66");
			check(onTop <= Math.max(level - 66.0F, 0.0F) + 1.0e-4F, String.format("no water is stood up on the new blocks: depth on top %.3f (pool level was %.3f)", onTop, level));
			for (int f = 0; f < 600; f++) {
				tiled.frame();
			}
			check(Math.abs(tiled.volume() - v2) / v2 < 1.0e-5, String.format("volume after settling %.4f (was %.4f)", tiled.volume(), v2));
		}

		System.out.println("Block sync: spans -> block amounts -> spans");
		{
			Solid solid = (x, y, z) -> y < 64 || y == 70 && x < 4;
			HfTileGrid grid = new HfTileGrid(0, 0, 40, 96, C, L, 0.25F, 4, 16, 16);
			HfTileGrid.Tile t = grid.build(voxels(solid), 0, 0);
			// Block amounts: column x=8,z=8 holds 2 full blocks and a 3/8 block on top; x=2,z=2 (under the ledge) 1 full block.
			HfBlocks.Amounts blocks = (x, y, z) -> {
				if (x == 8 && z == 8) {
					return y == 64 || y == 65 ? 8 : y == 66 ? 3 : 0;
				}
				return x == 2 && z == 2 && y == 64 ? 8 : 0;
			};
			float[] depth = HfBlocks.depthFromBlocks(grid, 0, 0, t.geo, t.layersUsed, blocks);
			int col = (8 * C) * grid.tn + 8 * C;
			check(Math.abs(depth[col] - 2.375F) < 1.0e-6F, "import: column depth 2 + 3/8 = " + depth[col]);
			int under = (2 * C) * grid.tn + 2 * C;
			float underDepth = 0.0F;
			for (int l = 0; l < t.layersUsed; l++) {
				int gg = t.geo[l * grid.pageSpans + under];
				if (HfTileGrid.validGeo(gg) && grid.floorOf(gg) == 64.0F) {
					underDepth = depth[l * grid.pageSpans + under];
				}
			}
			check(t.layersUsed == 2 && underDepth == 1.0F, "under the ledge: 2 layers, the lower one holds 1 block of water (" + underDepth + ")");
			boolean same = true;
			for (int bz = 0; bz < 16; bz++) {
				for (int bx = 0; bx < 16; bx++) {
					float[] fill = HfBlocks.blockFills(grid, t.geo, t.layersUsed, depth, bx, bz);
					for (int y = 40; y < 96; y++) {
						int back = HfBlocks.amountFor(fill[y - 40], 0);
						int x = bx;
						int z = bz;
						if (back != blocks.at(x, y, z)) {
							same = false;
						}
					}
				}
			}
			check(same, "write-back gives the same block amounts");
			check(HfBlocks.amountFor(0.03F, 0) == 0, "a 3 cm film writes no block (air)");
			check(HfBlocks.amountFor(0.40F, 3) == 3, "hysteresis keeps 3/8 while the fill stays within 3/4 of a step");
		}

		System.out.println("Reservoirs: an edge held at its level fills or drains a pool to it, a spring fills its basin and stops");
		{
			// Basin: floor at 64, walls 3 high around x,z in [2,13].
			Solid solid = (x, y, z) -> y < 64 || y < 67 && (x < 2 || x > 13 || z < 2 || z > 13);
			for (float start : new float[]{0.0F, 2.0F}) {
				HfTileGrid grid = new HfTileGrid(0, 0, 40, 96, C, L, 0.25F, 4, 16, 16);
				HfTiledSim tiled = new HfTiledSim(grid, voxels(solid));
				tiled.maxNewTiles = 0;
				int tile = tiled.addTile(0, 0, (l, c) -> start);
				grid.setReservoir(tile, (7 * C) * grid.tn + 2 * C, 65.0F, HfTileGrid.RESERVOIR_FIXED);
				for (int f = 0; f < 3600; f++) {
					tiled.frame();
				}
				float mid = tiled.depthAt(tile, 0, (8 * C) * grid.tn + 8 * C);
				check(Math.abs(mid - 1.0F) < 0.02F, String.format("pool starting %.0f deep settles to the edge level 65: depth %.3f", start, mid));
			}
			HfTileGrid grid = new HfTileGrid(0, 0, 40, 96, C, L, 0.25F, 4, 16, 16);
			HfTiledSim tiled = new HfTiledSim(grid, voxels(solid));
			tiled.maxNewTiles = 0;
			int tile = tiled.addTile(0, 0, null);
			grid.setReservoir(tile, (7 * C) * grid.tn + 7 * C, 66.0F, HfTileGrid.RESERVOIR_FIXED);
			for (int f = 0; f < 3600; f++) {
				tiled.frame();
			}
			float mid = tiled.depthAt(tile, 0, (10 * C) * grid.tn + 10 * C);
			double vol = tiled.volume();
			check(Math.abs(mid - 2.0F) < 0.03F, String.format("spring basin fills to the spring top (66): depth %.3f", mid));
			int extra = 0;
			for (int f = 0; f < 600; f++) {
				extra += tiled.frame();
			}
			check(Math.abs(tiled.volume() - vol) / vol < 1.0e-3, String.format("and stops there: volume %.2f -> %.2f", vol, tiled.volume()));
			double before = tiled.volume();
			tiled.inject(tile, (4 * C) * grid.tn + 4 * C, 66.5F, 1.0F);
			check(Math.abs(tiled.volume() - before - 0.25) < 1.0e-4, "inject adds depth x cell area: +" + (tiled.volume() - before));
		}

		System.out.println("Stirring: a body wading through a pool pushes a bow wave and leaves a wake, volume exact");
		{
			Solid solid = (x, y, z) -> y < 64 || (x == 0 || x == 15 || z == 0 || z == 15) && y < 67;
			HfTileGrid grid = new HfTileGrid(0, 0, 40, 96, C, L, 0.25F, 4, 16, 16);
			HfTiledSim tiled = new HfTiledSim(grid, voxels(solid));
			tiled.maxNewTiles = 0;
			int tn = grid.tn;
			IntPredicate pool = col -> col % tn >= C && col % tn < 15 * C && col / tn >= C && col / tn < 15 * C;
			int tile = tiled.addTile(0, 0, (l, col) -> l == 0 && pool.test(col) ? 1.2 : 0.0);
			double v0 = tiled.volume();
			float rest = 65.2F;
			float bow = 0.0F;
			float trough = 0.0F;
			float side = 0.0F;
			float foamPeak = 0.0F;
			for (int f = 0; f < 240; f++) {
				float t = f / 60.0F;
				boolean moving = t < 3.6F;
				float x = 3.0F + 2.5F * Math.min(t, 3.6F);
				tiled.stir = new float[]{x - 0.3F, 64.0F, 7.7F, 0.0F, x + 0.3F, 65.8F, 8.3F, 1.0F, moving ? 2.5F : 0.0F, 0.0F, 0.0F, 0.0F};
				grid.quiet[tile] = 0.0F;
				tiled.frame();
				if (moving && x > 4.0F && x < 11.0F) {
					bow = Math.max(bow, surfaceAt(tiled, tile, x + 0.75F, 8.0F) - rest);
					trough = Math.min(trough, surfaceAt(tiled, tile, x - 0.75F, 8.0F) - rest);
					foamPeak = Math.max(foamPeak, tiled.foamAt(tile, 0, (int) (8.0F * C) * tn + (int) (x * C)));
				}
				side = Math.max(side, Math.abs(surfaceAt(tiled, tile, 7.0F, 10.5F) - rest));
			}
			check(bow > 0.1F && bow < 0.4F, String.format("wading at 2.5 b/s through 1.2 deep water: bow wave ahead +%.3f", bow));
			check(trough < -0.02F, String.format("trough behind it: %.3f", trough));
			check(side > 0.005F, String.format("the wake spreads sideways: %.3f at 2.5 blocks", side));
			check(foamPeak > 0.03F && foamPeak < 0.35F, String.format("a little foam stirred up around the body: %.2f", foamPeak));
			check(Math.abs(tiled.volume() - v0) / v0 < 1.0e-5, String.format("volume %.4f -> %.4f", v0, tiled.volume()));

			HfTileGrid g2 = new HfTileGrid(0, 0, 40, 96, C, L, 0.25F, 4, 16, 16);
			HfTiledSim still = new HfTiledSim(g2, voxels(solid));
			still.maxNewTiles = 0;
			int t2 = still.addTile(0, 0, (l, col) -> l == 0 && pool.test(col) ? 1.2 : 0.0);
			float wave = 0.0F;
			for (int f = 0; f < 180; f++) {
				// Jumping in: crossing the surface for a quarter second pushes water out around the body.
				float plunge = f < 15 ? 6.0F : 0.0F;
				still.stir = new float[]{7.7F, 64.2F, 7.7F, plunge, 8.3F, 66.0F, 8.3F, 1.0F, 0.0F, -8.0F, 0.0F, 0.0F};
				g2.quiet[t2] = 0.0F;
				still.frame();
				wave = Math.max(wave, Math.abs(surfaceAt(still, t2, 11.0F, 8.0F) - rest));
			}
			check(wave > 0.004F, String.format("jumping in sends a ring out: %.3f at 3 blocks", wave));
			check(Math.abs(still.volume() - v0) / v0 < 1.0e-5, "volume kept");
		}

		System.out.println("Foam: water pouring off a 2-block ledge turns white where it lands, then fades");
		{
			Solid solid = (x, y, z) -> y < 64 || x < 6 && y < 66 || (x == 0 || x == 15 || z == 0 || z == 15) && y < 69;
			HfTileGrid grid = new HfTileGrid(0, 0, 40, 96, C, L, 0.25F, 4, 16, 16);
			HfTiledSim tiled = new HfTiledSim(grid, voxels(solid));
			tiled.maxNewTiles = 0;
			int tn = grid.tn;
			int tile = tiled.addTile(0, 0, (l, col) -> l == 0 && col % tn >= C && col % tn < 6 * C && col / tn >= C && col / tn < 15 * C ? 1.0 : 0.0);
			double v0 = tiled.volume();
			float landing = 0.0F;
			float upstream = 0.0F;
			for (int f = 0; f < 90; f++) {
				tiled.frame();
				landing = Math.max(landing, tiled.foamAt(tile, 0, (8 * C) * tn + 6 * C));
				upstream = Math.max(upstream, tiled.foamAt(tile, 0, (8 * C) * tn + 2 * C));
			}
			check(landing > 0.5F, String.format("foam where the water lands: %.2f", landing));
			check(upstream < 0.05F, String.format("calm water on the ledge stays clear: %.3f", upstream));
			float later = 0.0F;
			for (int f = 0; f < 1800; f++) {
				tiled.frame();
			}
			for (int col = 0; col < tn * tn; col++) {
				later = Math.max(later, tiled.foamAt(tile, 0, col));
			}
			check(later < 0.05F, String.format("30 s later the foam has faded: %.3f", later));
			check(Math.abs(tiled.volume() - v0) / v0 < 1.0e-5, "volume kept");
		}

		System.out.println("Edits: a tile rebuilt for a block edit keeps its flow (the water does not stop and surge back)");
		{
			Solid solid = (x, y, z) -> y < 64 || (z < 4 || z > 11) && y < 70;
			HfTileGrid grid = new HfTileGrid(0, 0, 40, 96, C, L, 0.25F, 4, 16, 16);
			HfTiledSim tiled = new HfTiledSim(grid, voxels(solid));
			tiled.maxNewTiles = 0;
			int tn = grid.tn;
			int tile = tiled.addTile(0, 0, null);
			for (int z = 4; z < 12; z++) {
				grid.setReservoir(tile, (z * C) * tn + 1, 65.0F, HfTileGrid.RESERVOIR_FIXED);
				grid.setReservoir(tile, (z * C) * tn + tn - 2, 64.3F, HfTileGrid.RESERVOIR_FIXED);
			}
			for (int f = 0; f < 900; f++) {
				tiled.frame();
			}
			double before = totalFlux(tiled, grid, tile);
			grid.markDirtyBlock(8, 66, 2);
			tiled.frame();
			double after = totalFlux(tiled, grid, tile);
			check(before > 0.1 && after > 0.8 * before, String.format("a steady channel flow keeps going through the rebuild: flux %.3f -> %.3f", before, after));
		}

		System.out.println("Edits: breaking a block that opens a new layer (a pocket in the bank) keeps the channel's flow");
		{
			boolean[] broken = {false};
			// The banks are solid up to y 70; breaking (8, 66, 2) opens a pocket inside the bank, so that column gets a second layer.
			Solid solid = (x, y, z) -> !(broken[0] && x == 8 && y == 66 && z == 2) && (y < 64 || (z < 4 || z > 11) && y < 70);
			HfTileGrid grid = new HfTileGrid(0, 0, 40, 96, C, L, 0.25F, 4, 16, 16);
			HfTiledSim tiled = new HfTiledSim(grid, voxels(solid));
			tiled.maxNewTiles = 0;
			int tn = grid.tn;
			int tile = tiled.addTile(0, 0, null);
			int layersBefore = grid.layersUsed[tile];
			for (int z = 4; z < 12; z++) {
				grid.setReservoir(tile, (z * C) * tn + 1, 65.0F, HfTileGrid.RESERVOIR_FIXED);
				grid.setReservoir(tile, (z * C) * tn + tn - 2, 64.3F, HfTileGrid.RESERVOIR_FIXED);
			}
			for (int f = 0; f < 900; f++) {
				tiled.frame();
			}
			double before = totalFlux(tiled, grid, tile);
			broken[0] = true;
			grid.markDirtyBlock(8, 66, 2);
			tiled.frame();
			double after = totalFlux(tiled, grid, tile);
			check(grid.layersUsed[tile] > layersBefore, "the pocket adds a layer (" + layersBefore + " -> " + grid.layersUsed[tile] + ")");
			check(before > 0.1 && after > 0.8 * before, String.format("the flow keeps going through the new layer: flux %.3f -> %.3f", before, after));
			for (int f = 0; f < 60; f++) {
				tiled.frame();
			}
			double later = totalFlux(tiled, grid, tile);
			check(later > 0.8 * before && later < 1.25 * before, String.format("and settles back without a surge: flux %.3f a second later", later));
		}

		System.out.println("Edits: taken-over water that is still a vanilla block (kelp, seagrass, unconverted water) stays put through a rebuild");
		{
			double[] old = foreignLake(false);
			double[] now = foreignLake(true);
			check(old[3] < 0.1 * old[2], String.format("old rule (non-realistic fluid is solid): breaking a log on the shore turns the lake solid, water %.1f -> %.1f (the old remap stood it up as a tower instead)", old[2], old[3]));
			check(Math.abs(now[1] - now[0]) < 0.05 && Math.abs(now[3] - now[2]) < 1.0e-3,
				String.format("kept open where the heightfield holds it: the lake stays at %.2f -> %.2f, water %.1f -> %.1f", now[0], now[1], now[2], now[3]));
		}

		System.out.println("Waterfalls: water pouring over a high drop is in the air for its fall time before it lands");
		{
			double[] r = cliffArrival();
			double expect = HfTiledSim.fallTime(30.0F);
			check(r[0] >= expect && r[0] < expect + 0.6, String.format("30-block drop: first water lands after %.2f s (fall time with air drag %.2f s, free fall %.2f s; was instant)",
				r[0], expect, Math.sqrt(2.0 * 30.0 / 9.81)));
			check(r[5] > 0.5, String.format("up to %.1f blocks^3 in the air at once", r[5]));
			check(Math.max(r[1] - r[2], r[3] - r[1]) < 1.0e-3 * r[1] && Math.abs(r[4] - r[1]) < 1.0e-3 * r[1],
				String.format("volume exact with the water in the air counted: %.2f, between %.2f and %.2f, %.2f at the end", r[1], r[2], r[3], r[4]));
		}

		System.out.println("Small streams: water landing on a block top spreads over it and pours off its edges, and the sheets are drawn");
		{
			for (float q : new float[]{0.01F, 0.05F, 0.2F}) {
				int[] before = trickleOnBlock(q, 0.03F, 0.005F);
				int[] now = trickleOnBlock(q, MIN_FLOW, HfMeshMirror.CURTAIN_MIN);
				check(now[0] >= Math.max(2 * before[0], 4) && now[1] >= before[1], String.format(
					"%.2f blocks^3/s on a 2x2 top: %d of 16 edge cells drawn pouring, %d of 16 top cells wet (before: %d and %d, 3 cm film rule, 0.005 sheets)",
					q, now[0], now[1], before[0], before[1]));
			}
		}

		System.out.println("Running water keeps its tile awake: a steady trickle has a still surface, but it must not sleep and freeze");
		{
			double[] r = streamTiles();
			check(r[0] == 0 && r[1] > 1.0, String.format("small stream down terraces over 4 tiles: %d tile-frames with running water left unsimulated (in the last 60 s), %.1f blocks^3 reached the 4th tile",
				(int) r[0], r[1]));
		}

		System.out.println("Thin running films do not pop in and out: drawn or hidden switches only after 0.3 s of running or standing");
		for (float q : new float[]{0.05F, 0.2F}) {
			double[] before = filmFlicker(q, false);
			double[] now = filmFlicker(q, true);
			check(now[0] < 0.2 * before[0] + 0.5 && now[1] <= 4 && now[2] > 0.8 * before[2], String.format(
				"%.2f b^3/s down terraces: %.1f repeated switches a second over the tile, at most %d per span in 20 s, %.0f spans drawn (frame by frame: %.1f, %d, %.0f)",
				q, now[0], (int) now[1], now[2], before[0], (int) before[1], before[2]));
		}

		System.out.println("Boats: vanilla boat buoyancy on the blocks the heightfield writes (AbstractBoatMixin)");
		HfBoatMirror.run();

		System.out.println("Topology: skipping the open sky above the heightmap gives exactly the same spans and links");
		{
			long fullMask = (1L << (C * C * C)) - 1L;
			HfTopology.Voxels plain = (x, y, z) -> {
				int surface = 70 + (int) (6 * Math.sin(x * 0.21) + 5 * Math.cos(z * 0.17));
				boolean cave = Math.sin(x * 0.3 + y * 0.2) * Math.cos(z * 0.25 - y * 0.15) > 0.7 && y < 60;
				boolean overhang = y == surface + 3 && (x + z) % 5 == 0;
				return (y < surface && !cave) || overhang ? fullMask : 0L;
			};
			HfTopology.Voxels sky = new HfTopology.Voxels() {
				public long blockVoxels(int x, int y, int z) {
					return plain.blockVoxels(x, y, z);
				}

				public int top(int x, int z) {
					return 70 + (int) (6 * Math.sin(x * 0.21) + 5 * Math.cos(z * 0.17)) + 4;
				}
			};
			HfTopology a = HfTopology.build(plain, -1, -1, 18, 18, -1, 320, C, L, 0.25F);
			HfTopology b = HfTopology.build(sky, -1, -1, 18, 18, -1, 320, C, L, 0.25F);
			check(java.util.Arrays.equals(a.floor, b.floor) && java.util.Arrays.equals(a.ceil, b.ceil) && java.util.Arrays.equals(a.links, b.links),
				"hilly terrain with caves and overhangs, window -1..320: identical with and without the sky skip");
		}

		System.out.println("Chunks streaming back in: a tile rebuilt while a neighbor chunk is unloaded loses the water flowing over that border");
		{
			double[] blind = blindRebuild(true);
			double[] seen = blindRebuild(false);
			check(blind[1] < blind[0] - 1.0, String.format("rebuilt blind (neighbor read as solid, it still links back): water %.1f -> %.1f", blind[0], blind[1]));
			check(Math.abs(seen[1] - seen[0]) < 1.0e-3 * seen[0], String.format("rebuilt once the neighbor is loaded (WaterWorld now waits for that): water %.1f -> %.1f", seen[0], seen[1]));
		}

		System.out.println("Edits: a block placed into a lake replaces its water and splits the column without lifting the water over it");
		for (int y : new int[]{59, 61, 64}) {
			boolean[] placed = {false};
			HfCpuTest.Solid solid = (x, yy, z) -> placed[0] && x == 10 && z == 8 && yy == y || (x < 6 ? yy < 65 : yy < 59);
			HfTileGrid grid = new HfTileGrid(0, 0, 40, 96, C, L, 0.25F, 4, 16, 16);
			HfTiledSim sim = new HfTiledSim(grid, voxels(solid));
			sim.maxNewTiles = 0;
			sim.addTile(0, 0, (l, c) -> (c % grid.tn) / C >= 6 && l == 0 ? 64.8 - 59.0 : 0.0);
			for (int f = 0; f < 30; f++) {
				sim.frame();
			}
			float before = maxSurface(sim, grid);
			double vol = sim.volume();
			placed[0] = true;
			grid.markDirtyBlock(10, y, 8);
			sim.frame();
			float after = maxSurface(sim, grid);
			double lost = vol - sim.volume();
			double own = Math.clamp(64.8 - y, 0.0, 1.0);
			check(after - before < 0.05F && Math.abs(lost - own) < 0.02,
				String.format("block at y %d: highest surface %.3f -> %.3f, water removed %.3f (the %.1f in its cell)", y, before, after, lost, own));
		}

		System.out.println("Splashes: an impact's crater and ring scale with its speed, volume exact");
		{
			Solid solid = (x, y, z) -> y < 60 || (x == 0 || x == 15 || z == 0 || z == 15) && y < 70;
			float[] waves = new float[2];
			float[] speeds = {5.0F, 78.0F};
			for (int k = 0; k < 2; k++) {
				HfTileGrid grid = new HfTileGrid(0, 0, 40, 96, C, L, 0.25F, 4, 16, 16);
				HfTiledSim tiled = new HfTiledSim(grid, voxels(solid));
				tiled.maxNewTiles = 0;
				int tn = grid.tn;
				int tile = tiled.addTile(0, 0, (l, col) -> l == 0 && col % tn >= C && col % tn < 15 * C && col / tn >= C && col / tn < 15 * C ? 4.0 : 0.0);
				double v0 = tiled.volume();
				// Same sizing as HfWorld.splash.
				double volume = Math.min(0.4 * Math.pow(speeds[k] / 5.0, 1.5), 40.0);
				double r = Math.clamp(0.5 + 0.35 * Math.sqrt(volume), 0.6, 4.0);
				double d = Math.min(2.0 * volume / (Math.PI * r * r), 0.8 * 4.0);
				crater(tiled, 8.0, 8.0, 64.0F, r, d);
				check(Math.abs(tiled.volume() - v0) / v0 < 1.0e-5, String.format("%.0f b/s impact: crater and ring keep the volume", speeds[k]));
				for (int f = 0; f < 120; f++) {
					grid.quiet[tile] = 0.0F;
					tiled.frame();
					waves[k] = Math.max(waves[k], Math.abs(surfaceAt(tiled, tile, 11.0F, 8.0F) - 64.0F));
				}
			}
			check(waves[0] > 0.005F && waves[0] < 0.05F, String.format("jumping in (5 b/s) rings the surface lightly: %.3f at 3 blocks", waves[0]));
			check(waves[1] > 10.0F * waves[0], String.format("falling at terminal speed (78 b/s) throws a real wave: %.3f at 3 blocks", waves[1]));
		}

		System.out.println("Mesh: spans drawn as one surface share their corners exactly (no cracks), streams over steps become ramps");
		HfMeshMirror.run();

		System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}
}
