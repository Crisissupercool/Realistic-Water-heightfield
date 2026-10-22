package me.skaffy.client.water;

/** CPU mirror of hf_mesh.comp's corner(): spans drawn as one surface must agree on every shared corner (height and slope). If you change corner(), linked() or wet() in the shader, change this the same way. */
final class HfMeshMirror {
	static HfTiledSim sim;
	static HfTileGrid g;
	static final float DRY = 0.035F, FLOW_DRY = 0.008F, RAMP_MAX = 1.6F, RAMP_FLOW = 0.02F, DROP = 0.111F;

	record S(int tile, int layer, int col) {
		int span() { return g.spanPage[tile * 4 + layer] * g.pageSpans + col; }
		int flux() { int fp = g.fluxPage[tile * 4 + layer]; return fp < 0 ? -1 : (fp * g.pageSpans + col) * 8; }
	}

	static float floorOf(S s) { return g.floorOf(sim.geo[s.span()]); }
	static float ceilOf(S s) { return g.ceilOf(sim.geo[s.span()]); }
	static float depth(S s) { return sim.depth[s.span()]; }
	static float surface(S s) { return Math.min(floorOf(s) + depth(s), ceilOf(s)); }
	static float outTotal(S s) { int f = s.flux(); float o = 0; if (f >= 0) for (int k = 0; k < 8; k++) o += sim.flux[f + k]; return o; }
	static boolean wet(S s) { float d = depth(s); if (d > DRY) return true; if (d <= FLOW_DRY) return false; return outTotal(s) > 0.002F; }
	static boolean joined(S a, S t) { return wet(t) && floorOf(t) < surface(a) && floorOf(a) < surface(t); }
	static float outTo(S a, int dir) { int f = a.flux(); return f < 0 ? 0 : sim.flux[f + dir * 2] + sim.flux[f + dir * 2 + 1]; }
	static boolean linked(S a, int dir, S t) {
		if (!wet(a) || !wet(t)) return false;
		if (joined(a, t)) return true;
		if (Math.abs(surface(a) - surface(t)) > RAMP_MAX) return false;
		return outTo(a, dir) + outTo(t, dir ^ 1) > RAMP_FLOW;
	}
	static S neighbor(S a, int k, int nib) {
		int tn = g.tn, d = k >> 1;
		int cx = a.col % tn + HfTileGrid.DX[d], cz = a.col / tn + HfTileGrid.DZ[d], t = a.tile;
		if (cx < 0 || cz < 0 || cx >= tn || cz >= tn) { t = g.nbr[a.tile * 4 + d]; if (t < 0) return null; cx = (cx + tn) % tn; cz = (cz + tn) % tn; }
		int layer = nib & 3;
		if (g.spanPage[t * 4 + layer] < 0) return null;
		return new S(t, layer, cz * tn + cx);
	}
	static S pick(S a, int dir) {
		S found = null;
		for (int j = 0; j < 2; j++) {
			int k = dir * 2 + j, nib = HfTileGrid.link(sim.links[a.span()], k);
			if (nib == HfTileGrid.LINK_NONE) continue;
			S t = neighbor(a, k, nib);
			if (t == null) continue;
			if (linked(a, dir, t)) return t;
			if (found == null) found = t;
		}
		return found;
	}
	static float sample(boolean ok, boolean link, S t, float lo, float level) {
		if (!ok) return Float.NaN;
		if (link) return surface(t);
		if (wet(t)) return Float.NaN;
		float nf = floorOf(t);
		if (nf < lo - 0.05F) return lo + (level - lo) * 0.7F;
		return Math.clamp(nf, lo, level);
	}
	static float lastGx, lastGz, normalGap;
	static float corner(S a, int sx, int sz) {
		float fl = floorOf(a), top = surface(a), ce = ceilOf(a);
		int dirX = sx > 0 ? 0 : 1, dirZ = sz > 0 ? 2 : 3;
		S nx = pick(a, dirX), nz = pick(a, dirZ), nd = null;
		boolean okX = nx != null, okZ = nz != null;
		boolean eSX = okX && linked(a, dirX, nx), eSZ = okZ && linked(a, dirZ, nz);
		S dx = okX ? pick(nx, dirZ) : null, dz = okZ ? pick(nz, dirX) : null;
		boolean okDX = dx != null, okDZ = dz != null;
		boolean eXD = okDX && linked(nx, dirZ, dx), eZD = okDZ && linked(nz, dirX, dz);
		boolean okD = okDX || okDZ;
		boolean same = okDX && okDZ && dx.equals(dz);
		if (okDX && (eXD || !(okDZ && eZD))) { nd = dx; eZD = same && eZD; }
		else if (okDZ) { nd = dz; eXD = same && eXD; }
		boolean lx = eSX || (eSZ && eZD && eXD), lz = eSZ || (eSX && eXD && eZD), ld = (eSX && eXD) || (eSZ && eZD);
		float levelSum = top, lo = fl, hiC = ce, depthSum = top - fl, shares = 1;
		for (Object[] o : new Object[][]{{lx, nx}, {lz, nz}, {ld, nd}}) {
			if ((Boolean) o[0]) { S t = (S) o[1]; levelSum += surface(t); lo = Math.min(lo, floorOf(t)); hiC = Math.min(hiC, ceilOf(t)); depthSum += surface(t) - floorOf(t); shares++; }
		}
		float level = levelSum / shares;
		float sum = top, count = 1;
		float[] v3 = {sample(okX, lx, nx, lo, level), sample(okZ, lz, nz, lo, level), sample(okD, ld, nd, lo, level)};
		for (float v : v3) {
			if (!Float.isNaN(v)) { sum += v; count++; }
		}
		float hh = sum / count;
		float hx = Float.isNaN(v3[0]) ? hh : v3[0], hz = Float.isNaN(v3[1]) ? hh : v3[1], hd = Float.isNaN(v3[2]) ? hh : v3[2];
		lastGx = ((hx - top) + (hd - hz)) * 0.5F / 0.5F * sx;
		lastGz = ((hz - top) + (hd - hx)) * 0.5F / 0.5F * sz;
		float h = Math.clamp(hh, lo, hiC);
		h -= DROP * Math.clamp(Math.min(depthSum / shares, h - lo), 0, 1);
		return h;
	}

	static boolean check(String name, HfCpuTest.Solid solid, int resCol, float resLevel, int frames) {
		int C = 2;
		g = new HfTileGrid(0, 0, 40, 96, C, 4, 0.25F, 8, 32, 32);
		sim = new HfTiledSim(g, HfCpuTest.voxels(solid));
		sim.maxNewTiles = 4;
		int tile = sim.addTile(0, 0, null);
		sim.addTile(1, 0, null);
		g.setReservoir(tile, resCol, resLevel, HfTileGrid.RESERVOIR_FIXED);
		for (int f = 0; f < frames; f++) sim.frame();
		int tn = g.tn, pairs = 0, ramps = 0, cracks = 0, wetCount = 0;
		float worst = 0;
		for (int t = 0; t < g.count; t++) for (int l = 0; l < g.layersUsed[t]; l++) for (int col = 0; col < g.pageSpans; col++) {
			S a = new S(t, l, col);
			if (!HfTileGrid.validGeo(sim.geo[a.span()]) || !wet(a)) continue;
			wetCount++;
			for (int dir : new int[]{0, 2}) {
				S b = pick(a, dir);
				if (b == null || !linked(a, dir, b)) continue;
				pairs++;
				if (!joined(a, b)) ramps++;
				int[][] pairsC = dir == 0 ? new int[][]{{1, -1, -1, -1}, {1, 1, -1, 1}} : new int[][]{{-1, 1, -1, -1}, {1, 1, 1, -1}};
				float d = 0;
				for (int[] pc : pairsC) {
					float h1 = corner(a, pc[0], pc[1]); float gx1 = lastGx, gz1 = lastGz;
					float h2 = corner(b, pc[2], pc[3]); float gx2 = lastGx, gz2 = lastGz;
					d = Math.max(d, Math.abs(h1 - h2));
					normalGap = Math.max(normalGap, Math.max(Math.abs(gx1 - gx2), Math.abs(gz1 - gz2)));
				}
				worst = Math.max(worst, d);
				if (d > 1e-4F) {
					cracks++;
				}
			}
		}
		boolean ok = cracks == 0 && normalGap < 1.0e-4F && ramps > 0 == !name.startsWith("lake");
		HfCpuTest.check(ok, String.format("%s: %d wet spans, %d pairs drawn as one surface (%d ramps over steps), %d cracks (worst %.4f), worst slope mismatch %.4f",
			name, wetCount, pairs, ramps, cracks, worst, normalGap));
		normalGap = 0;
		return ok;
	}

	// ---- Falling sheets (hf_mesh.comp curtainOf, curtainEnd, sheetPoint) ----
	static final float CURTAIN_MIN = 0.005F, REACH = 0.42F, GRAV = 9.81F;
	static final float[] CURTAIN_T = {0.0F, 0.05F, 0.13F, 0.26F, 0.45F, 0.7F, 1.0F};
	static final int[] SIDE0 = {3, 2, 0, 1}, SIDE1 = {2, 3, 1, 0};
	/** Corner signs (sx, sz) of each edge's two ends, as in hf_mesh.comp's e0s / e1s. */
	static final int[][] END0 = {{1, -1}, {-1, 1}, {1, 1}, {-1, -1}}, END1 = {{1, 1}, {-1, -1}, {-1, 1}, {1, -1}};
	static final float[][] OUT = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	static float[] curtainOf(S t, int d) {
		if (t.flux() < 0) return null;
		S n = pick(t, d);
		if (n == null || linked(t, d, n)) return null;
		float fl = floorOf(t);
		float land = wet(n) ? surface(n) : floorOf(n);
		float q = outTo(t, d);
		if (land >= fl - 0.05F || q <= CURTAIN_MIN) return null;
		return new float[]{land, Math.clamp(q / (0.5F * Math.max(surface(t) - fl, 0.03F)), 0.2F, 4.0F)};
	}

	static float[] curtainEnd(S a, int d, int sd, float[] c) {
		S n = pick(a, sd);
		if (n != null && linked(a, sd, n)) {
			float[] nc = curtainOf(n, d);
			if (nc != null) return new float[]{0.5F * (c[0] + nc[0]), 0.5F * (c[1] + nc[1]), 0.5F * (floorOf(a) + floorOf(n)), 1};
		}
		return new float[]{c[0], c[1], floorOf(a), 0};
	}

	/** Corner point of span a (world xz, corner height) at signs (sx, sz). */
	static float[] cornerPoint(S a, int sx, int sz) {
		int tn = g.tn, cx = g.tileX[a.tile] * tn + a.col % tn, cz = g.tileZ[a.tile] * tn + a.col / tn;
		return new float[]{(cx + (sx > 0 ? 1 : 0)) * 0.5F, corner(a, sx, sz), (cz + (sz > 0 ? 1 : 0)) * 0.5F};
	}

	/** {top xyz, bottom xyz, n xyz} like sheetPoint (no free-end narrowing). */
	static float[] sheetPoint(float[] e, float[] o, float[] end, float s) {
		float v0 = end[1], h = Math.max(e[1] - end[0], 0), t = (float) Math.sqrt(2 * h / GRAV) * s;
		float k = (float) Math.exp(-v0 * t / REACH), vx = v0 * k, vy = GRAV * t, sp = (float) Math.hypot(vx, vy);
		float[] n = {o[0] * vy / sp, vx / sp, o[1] * vy / sp};
		float x = REACH * (1 - k);
		float[] top = {e[0] + o[0] * x, e[1] - h * s * s, e[2] + o[1] * x};
		float floorY = Math.min(end[2], e[1]), hl = Math.max(floorY - end[0], 0), tl = (float) Math.sqrt(2 * hl / GRAV) * s;
		float xl = REACH * (1 - (float) Math.exp(-v0 * tl / REACH));
		float[] b = {e[0] + o[0] * xl, floorY - hl * s * s, e[2] + o[1] * xl};
		float gap = (top[0] - b[0]) * n[0] + (top[1] - b[1]) * n[1] + (top[2] - b[2]) * n[2];
		float push = Math.max(0.012F - gap, 0);
		for (int i = 0; i < 3; i++) b[i] -= n[i] * push;
		float outTop = (top[0] - e[0]) * o[0] + (top[2] - e[2]) * o[1];
		float outB = (b[0] - e[0]) * o[0] + (b[2] - e[2]) * o[1];
		float fix = Math.max(Math.min(0.004F, outTop) - outB, 0);
		b[0] += o[0] * fix;
		b[2] += o[1] * fix;
		b[1] = Math.max(b[1], end[0]);
		return new float[]{top[0], top[1], top[2], b[0], b[1], b[2], n[0], n[1], n[2]};
	}

	static void checkCurtains() {
		int C = 2;
		g = new HfTileGrid(0, 0, 40, 96, C, 4, 0.25F, 8, 32, 32);
		// A channel (z 6..10) at y 76 that drops 10 blocks at x = 12 onto a lower channel.
		sim = new HfTiledSim(g, HfCpuTest.voxels((x, y, z) -> (z < 6 || z > 10 || x < 2) ? y < 80 : y < (x < 12 ? 76 : 66)));
		sim.maxNewTiles = 4;
		int tile = sim.addTile(0, 0, null);
		sim.addTile(1, 0, null);
		for (int z = 6; z <= 10; z++) {
			g.setReservoir(tile, (z * C) * g.tn + 2 * C, 76.4F, HfTileGrid.RESERVOIR_FIXED);
		}
		for (int f = 0; f < 600; f++) sim.frame();
		int sheets = 0, sharedEnds = 0, freeEnds = 0, bad = 0;
		float worstShared = 0, thinnest = 9, worstInside = 0;
		for (int t = 0; t < g.count; t++) for (int l = 0; l < g.layersUsed[t]; l++) for (int col = 0; col < g.pageSpans; col++) {
			S a = new S(t, l, col);
			if (!HfTileGrid.validGeo(sim.geo[a.span()]) || !wet(a)) continue;
			for (int d = 0; d < 4; d++) {
				float[] c = curtainOf(a, d);
				if (c == null) continue;
				sheets++;
				for (int end = 0; end < 2; end++) {
					int sd = end == 0 ? SIDE0[d] : SIDE1[d];
					int[] sg = end == 0 ? END0[d] : END1[d];
					float[] en = curtainEnd(a, d, sd, c);
					float[] e = cornerPoint(a, sg[0], sg[1]);
					if (en[3] > 0.5F) {
						// The neighbor computes this same corner as its other end; every vertex there must be identical.
						sharedEnds++;
						S n = pick(a, sd);
						float[] nc = curtainOf(n, d);
						int[] nsg = end == 0 ? END1[d] : END0[d];
						float[] nen = curtainEnd(n, d, sd ^ 1, nc);
						float[] ne = cornerPoint(n, nsg[0], nsg[1]);
						for (float s : CURTAIN_T) {
							float[] p = sheetPoint(e, OUT[d], en, s), q = sheetPoint(ne, OUT[d], nen, s);
							for (int i = 0; i < 6; i++) worstShared = Math.max(worstShared, Math.abs(p[i] - q[i]));
						}
					} else {
						freeEnds++;
					}
					float prevBottom = Float.MAX_VALUE;
					for (float s : CURTAIN_T) {
						float[] p = sheetPoint(e, OUT[d], en, s);
						float gap = (p[0] - p[3]) * p[6] + (p[1] - p[4]) * p[7] + (p[2] - p[5]) * p[8];
						thinnest = Math.min(thinnest, gap);
						float outB = (p[3] - e[0]) * OUT[d][0] + (p[5] - e[2]) * OUT[d][1];
						worstInside = Math.max(worstInside, -outB);
						if (p[4] > prevBottom + 1e-5F || gap < 0.0119F) bad++;
						prevBottom = p[4];
					}
				}
			}
		}
		// Five block wide stream = 10 cells pouring over the cliff: one curtain, free only at its two sides.
		boolean ok = sheets == 10 && freeEnds == 2 && sharedEnds == 18 && worstShared < 1e-5F && worstInside <= 0 && bad == 0;
		HfCpuTest.check(ok, String.format("waterfall: %d cells pour over the cliff, %d shared ends (worst mismatch %.6f), %d free ends; ribbon at least %.3f thick, never inside the cliff (%.4f), inner surface only falls (%d bad)",
			sheets, sharedEnds, worstShared, freeEnds, thinnest, worstInside, bad));
	}

	static int hash(int x, int z) {
		int h = x * 374761393 + z * 668265263;
		h = (h ^ (h >>> 13)) * 1274126177;
		return h ^ (h >>> 16);
	}

	static void run() {
		int tn = 32;
		check("staircase stream", (x, y, z) -> (z < 6 || z > 10 || x < 2) ? y < 80 : y < (x < 22 ? 76 - (x - 2) / 2 : 64),
			(8 * 2) * tn + 2 * 2 + 1, 76.4F, 600);
		check("bumpy slope", (x, y, z) -> y < 74 - x / 3 + (Math.floorMod(hash(x, z), 3) - 1) || x < 1 && y < 80,
			(8 * 2) * tn + 2 * 2 + 1, 76.5F, 900);
		checkCurtains();
		check("lake with shore", (x, y, z) -> y < 64 + Math.max(0, Math.abs(x - 12) + Math.abs(z - 12) - 8) / 2,
			(12 * 2) * tn + 12 * 2, 65.3F, 900);
	}
}
