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

	/** Water surface at block coordinates (x, z) of a single-layer tile at the origin. */
	static float surfaceAt(HfTiledSim sim, int tile, float x, float z) {
		int col = (int) (z * C) * sim.g.tn + (int) (x * C);
		return sim.g.floorOf(sim.geo[sim.g.span(tile, 0, col)]) + sim.depthAt(tile, 0, col);
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
				boolean film = hA <= MIN_FLOW;
				float[] out = new float[8];
				float total = 0.0F;
				for (int k = 0; k < 8; k++) {
					int l = t.links[base + k];
					if (l == -1) {
						continue;
					}
					int b = l >>> 3;
					float headB = t.floor[b] + h[b];
					if (film && headB >= t.floor[s]) {
						continue;
					}
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
			// Fill a 4x4 block patch of the water with stone, 2 blocks high, at the bottom of the pool.
			double v1 = tiled.volume();
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
			check(Math.abs(v2 - v1) / v1 < 1.0e-6, String.format("placing 32 blocks in the water keeps the volume: %.4f -> %.4f", v1, v2));
			int col = 5 * C * tn + 5 * C;
			float onTop = tiled.depthAt(grid.slotAt(0, 0), 0, col);
			check(grid.floorOf(tiled.geo[grid.span(grid.slotAt(0, 0), 0, col)]) == 66.0F, "the patch raised the floor to 66");
			check(onTop > 0.0F, "the displaced water sits on top of the new blocks: depth " + onTop);
			for (int f = 0; f < 600; f++) {
				tiled.frame();
			}
			check(Math.abs(tiled.volume() - v1) / v1 < 1.0e-5, String.format("volume after settling %.4f (was %.4f)", tiled.volume(), v1));
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

		System.out.println("Mesh: spans drawn as one surface share their corners exactly (no cracks), streams over steps become ramps");
		HfMeshMirror.run();

		System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}
}
