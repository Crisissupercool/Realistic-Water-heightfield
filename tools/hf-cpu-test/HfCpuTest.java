package me.skaffy.client.water;

import java.util.function.IntPredicate;

/** CPU checks for HfTopology and a line-by-line Java mirror of hf_flux.comp / hf_depth.comp. Not part of the mod. */
public final class HfCpuTest {
	static final int C = 2;
	static final int L = 4;
	static int failures;
	/** Mirrors WaterSettings.hfMinFlowDepth: thinner water does not flow out (it stays as a film), so spreading stops. */
	static float MIN_FLOW = 0.03F;

	interface Solid {
		boolean at(int x, int y, int z);
	}

	static HfTopology.Voxels voxels(Solid solid) {
		return (bx, by, bz) -> solid.at(bx, by, bz) ? (1L << (C * C * C)) - 1L : 0L;
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
				if (!t.valid(s) || hA <= MIN_FLOW) {
					for (int k = 0; k < 8; k++) {
						flux[base + k] = 0.0F;
					}
					continue;
				}
				float headA = t.floor[s] + hA;
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
					float q = flux[base + k] * keep + dt * 9.81F * area * (headA - headB) / cell;
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
			HfTileGrid grid = new HfTileGrid(0, 0, 40, 96, C, L, 0.25F, 16);
			HfTiledSim tiled = new HfTiledSim(grid, voxels(solid));
			tiled.sleepRate = -1.0F;
			tiled.filmRate = -1.0F;
			tiled.maxNewTiles = 0;
			int tn = grid.tn;
			for (int tz = 0; tz < 3; tz++) {
				for (int tx = 0; tx < 3; tx++) {
					int ftx = tx;
					int ftz = tz;
					tiled.addTile(tx, tz, i -> {
						int col = i / L;
						return h0[dense.span(ftx * tn + col % tn, ftz * tn + col / tn, i % L)];
					});
				}
			}
			boolean topoSame = true;
			for (int cz = 0; cz < dense.nz && topoSame; cz++) {
				for (int cx = 0; cx < dense.nx; cx++) {
					for (int l = 0; l < L; l++) {
						int a = dense.span(cx, cz, l);
						int b = grid.span(grid.slotAt(cx / tn, cz / tn), cx % tn, cz % tn, l);
						if (dense.floor[a] != tiled.floor[b] || dense.ceil[a] != tiled.ceil[b]) {
							topoSame = false;
						}
						for (int k = 0; k < 8 && dense.valid(a); k++) {
							int la = dense.links[a * 8 + k];
							int lb = HfTileGrid.link(tiled.links, b, k);
							boolean edge = cx + HfTileGrid.DX[k >> 1] < 0 || cz + HfTileGrid.DZ[k >> 1] < 0
								|| cx + HfTileGrid.DX[k >> 1] >= dense.nx || cz + HfTileGrid.DZ[k >> 1] >= dense.nz;
							if (edge) {
								continue;
							}
							if ((la == -1) != (lb == HfTileGrid.LINK_NONE) || la != -1 && ((la >>> 3) % L != lb >> 3 || (la & 7) != (lb & 7))) {
								topoSame = false;
							}
						}
					}
				}
			}
			check(topoSame, "spans and links match the dense build, including across tile borders");
			Sim sim = new Sim(dense, h0);
			double v0 = sim.volume();
			float maxDiff = 0.0F;
			for (int f = 0; f < 600; f++) {
				sim.run(1);
				tiled.frame();
			}
			for (int cz = 0; cz < dense.nz; cz++) {
				for (int cx = 0; cx < dense.nx; cx++) {
					for (int l = 0; l < L; l++) {
						int a = dense.span(cx, cz, l);
						int b = grid.span(grid.slotAt(cx / tn, cz / tn), cx % tn, cz % tn, l);
						if (dense.valid(a)) {
							maxDiff = Math.max(maxDiff, Math.abs(sim.h[a] - tiled.h[b]));
						}
					}
				}
			}
			check(maxDiff < 1.0e-5F, String.format("after 10 s every depth matches the dense solver (max difference %.2e)", maxDiff));
			check(Math.abs(tiled.volume() - v0) / v0 < 1.0e-5, String.format("volume %.3f -> %.3f", v0, tiled.volume()));
			int inside = grid.span(grid.slotAt(0, 0), 15 * C, 15 * C, 1);
			check(tiled.h[inside] > 0.1F, "water crossed the tile border at x=16 into the room: depth " + tiled.h[inside]);
		}

		System.out.println("Tiles: water grows into new tiles (starts as 1 tile, column next to its +x border)");
		{
			Solid solid = (x, y, z) -> y < 64;
			HfTileGrid grid = new HfTileGrid(0, 0, 40, 96, C, L, 0.25F, 64);
			HfTiledSim tiled = new HfTiledSim(grid, voxels(solid));
			int tn = grid.tn;
			tiled.addTile(0, 0, i -> {
				int col = i / L;
				double x = (col % tn + 0.5) / C - 12.0;
				double z = (col / tn + 0.5) / C - 8.0;
				return i % L == 0 && x * x + z * z < 16.0 ? 2.0 : 0.0;
			});
			double v0 = tiled.volume();
			for (int f = 0; f < 1800; f++) {
				tiled.frame();
			}
			double drift = Math.abs(tiled.volume() - v0) / v0 * 100.0;
			check(grid.count > 1, "tiles allocated as the water spread: " + grid.count);
			check(grid.slotAt(1, 0) >= 0 && tiled.h[grid.span(grid.slotAt(1, 0), 4, tn / 2, 0)] > 0.0F, "water flowed into the new +x tile");
			check(drift < 0.1, String.format("volume drift %.4f%%", drift));
		}

		System.out.println("Tiles: settled water sleeps tile by tile, volume stays exact (open flat ground, wall at x=20)");
		{
			Solid solid = (x, y, z) -> y < 64 || x == 20 && y < 66;
			HfTileGrid grid = new HfTileGrid(0, 0, 40, 96, C, L, 0.25F, 64);
			HfTiledSim tiled = new HfTiledSim(grid, voxels(solid));
			for (int tz = 0; tz < 3; tz++) {
				for (int tx = 0; tx < 3; tx++) {
					int ftx = tx;
					int ftz = tz;
					tiled.addTile(tx, tz, i -> {
						int col = i / L;
						double x = ftx * 16 + (col % grid.tn + 0.5) / C - 10.0;
						double z = ftz * 16 + (col / grid.tn + 0.5) / C - 24.0;
						return i % L == 0 && x * x + z * z < 36.0 ? 3.0 : 0.0;
					});
				}
			}
			double v0 = tiled.volume();
			int asleepAt = -1;
			int minActive = Integer.MAX_VALUE;
			// On open flat ground the edge keeps creeping outward as a thin film for minutes; film tiles sleep at a relaxed rate.
			for (int f = 0; f < 400 * 60; f++) {
				tiled.frame();
				minActive = Math.min(minActive, grid.activeList.length);
				if (grid.activeList.length == 0) {
					asleepAt = f / 60;
					break;
				}
			}
			double drift = Math.abs(tiled.volume() - v0) / v0 * 100.0;
			check(asleepAt >= 0, "every tile asleep after " + asleepAt + " s (" + grid.count + " tiles)");
			check(drift < 0.1, String.format("volume drift %.4f%%", drift));
			check(tiled.frame() == 0, "no solver steps once asleep");
		}

		System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}
}
