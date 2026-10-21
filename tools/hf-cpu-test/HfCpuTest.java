package me.skaffy.client.water;

import java.util.function.IntPredicate;

/** CPU checks for HfTopology and a line-by-line Java mirror of hf_flux.comp / hf_depth.comp. Not part of the mod. */
public final class HfCpuTest {
	static final int C = 2;
	static final int L = 4;
	static int failures;

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
				if (!t.valid(s) || hA <= 0.0F) {
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

		System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}
}
