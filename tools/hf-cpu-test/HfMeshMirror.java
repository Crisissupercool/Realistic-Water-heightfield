package me.skaffy.client.water;

/** CPU mirror of hf_mesh.comp's corner(): spans drawn as one surface must agree on every shared corner (height and slope). If you change corner(), linked() or wet() in the shader, change this the same way. */
final class HfMeshMirror {
	static HfTiledSim sim;
	static HfTileGrid g;
	static final float DRY = 0.035F, FLOW_DRY = 0.008F, RAMP_MAX = 1.6F, RAMP_FLOW = 0.02F, RAMP_DEPTH = 0.35F, FLOW_DRAW = 0.0005F, DROP = 0.111F, SHORE_BELOW = 0.25F;
	/** The shader's marching-squares shoreline (corner remap and shore quads); false: the old cell-edged one, for comparison. */
	static boolean shore = true;

	record S(int tile, int layer, int col) {
		int span() { return g.spanPage[tile * 4 + layer] * g.pageSpans + col; }
		int flux() { int fp = g.fluxPage[tile * 4 + layer]; return fp < 0 ? -1 : (fp * g.pageSpans + col) * 8; }
	}

	static float floorOf(S s) { return g.floorOf(sim.geo[s.span()]); }
	static float ceilOf(S s) { return g.ceilOf(sim.geo[s.span()]); }
	static float depth(S s) { return sim.depth[s.span()]; }
	static float surface(S s) { return Math.min(floorOf(s) + depth(s), ceilOf(s)); }
	static float outTotal(S s) { int f = s.flux(); float o = 0; if (f >= 0) for (int k = 0; k < 8; k++) o += sim.flux[f + k]; return o; }
	/** The shader's presence() (waterline moving smoothly through cells); false: all or nothing, as before, for comparison. */
	static boolean smooth = true;
	static float presence(S s) {
		float d = depth(s);
		float v = sim.air[s.span() * HfTiledSim.AIR_STRIDE + 3];
		if (!smooth) return d > DRY || (d > FLOW_DRY && v >= 2.0F) ? 1.0F : 0.0F;
		if (s.flux() >= 0) return v >= 2.0F ? v - 2.0F : v;
		float still = Math.clamp((d - sim.renderDepth) / HfTiledSim.STILL_FADE, 0.0F, 1.0F);
		float film = v >= 2.0F ? Math.clamp((d - HfTiledSim.FILM_DRY) / (HfTiledSim.FILM_FULL - HfTiledSim.FILM_DRY), 0.0F, 1.0F) : 0.0F;
		return Math.max(still, film);
	}
	static boolean wet(S s) { return presence(s) > 0.0F; }
	static boolean joined(S a, S t) { return wet(t) && floorOf(t) < surface(a) && floorOf(a) < surface(t); }
	static float outTo(S a, int dir) { int f = a.flux(); return f < 0 ? 0 : sim.flux[f + dir * 2] + sim.flux[f + dir * 2 + 1]; }
	static boolean linked(S a, int dir, S t) {
		if (!wet(a) || !wet(t)) return false;
		if (joined(a, t)) return true;
		float drop = Math.abs(surface(a) - surface(t));
		if (drop > RAMP_MAX || Math.max(depth(a), depth(t)) < RAMP_DEPTH * drop) return false;
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
	static float drySample(float nf, float lo, float level) { return nf < lo - 0.05F ? lo + (level - lo) * 0.7F : Math.clamp(nf, lo, level); }
	static final float FILM_MIN_DRAWN = 0.008F;
	static float drawnSurface(S s) { return Math.min(floorOf(s) + Math.max(depth(s), smooth ? FILM_MIN_DRAWN : 0.0F), ceilOf(s)); }
	static float sample(boolean ok, boolean link, S t, float lo, float level) {
		if (!ok) return Float.NaN;
		float nf = floorOf(t);
		if (link) { float p = presence(t); return drySample(nf, lo, level) * (1 - p) + drawnSurface(t) * p; }
		if (wet(t)) return Float.NaN;
		if (!smooth) return drySample(nf, lo, level);
		if (Math.abs(nf - lo) > 0.05F) return Float.NaN;
		return lo;
	}
	static boolean flatLike(boolean ok, boolean link, S t, float lo) {
		return ok && (link ? presence(t) < 1.0F : !wet(t)) && Math.abs(floorOf(t) - lo) <= 0.05F;
	}
	static boolean dryFlat(boolean ok, boolean link, S t, float lo) { return ok && !link && !wet(t) && Math.abs(floorOf(t) - lo) <= 0.05F; }
	static boolean dryOther(boolean ok, boolean link, S t, float lo) { return ok && !link && !wet(t) && Math.abs(floorOf(t) - lo) > 0.05F; }
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
		float pa = presence(a);
		if (smooth) top = Math.max(top, Math.min(fl + FILM_MIN_DRAWN, ce));
		float levelSum = pa * top, lo = fl, hiC = ce, depthSum = pa * (top - fl), shares = pa;
		for (Object[] o : new Object[][]{{lx, nx}, {lz, nz}, {ld, nd}}) {
			if ((Boolean) o[0]) { S t = (S) o[1]; float p = presence(t); levelSum += p * drawnSurface(t); lo = Math.min(lo, floorOf(t)); hiC = Math.min(hiC, ceilOf(t)); depthSum += p * (drawnSurface(t) - floorOf(t)); shares += p; }
		}
		shares = Math.max(shares, 1.0e-6F);
		float level = levelSum / shares;
		float hs = drySample(fl, lo, level) * (1 - pa) + top * pa;
		float sum = hs, count = 1;
		float[] v3 = {sample(okX, lx, nx, lo, level), sample(okZ, lz, nz, lo, level), sample(okD, ld, nd, lo, level)};
		boolean flat = (pa < 1.0F && Math.abs(fl - lo) <= 0.05F) || flatLike(okX, lx, nx, lo) || flatLike(okZ, lz, nz, lo) || flatLike(okD, ld, nd, lo);
		boolean other = !smooth && (dryOther(okX, lx, nx, lo) || dryOther(okZ, lz, nz, lo) || dryOther(okD, ld, nd, lo));
		float[] p3 = {lx ? presence(nx) : 0, lz ? presence(nz) : 0, ld ? presence(nd) : 0};
		float cover = pa;
		for (int i = 0; i < 3; i++) {
			if (!Float.isNaN(v3[i])) { sum += v3[i]; count++; cover += p3[i]; }
		}
		float hh = sum / count;
		float hx = Float.isNaN(v3[0]) ? hh : v3[0], hz = Float.isNaN(v3[1]) ? hh : v3[1], hd = Float.isNaN(v3[2]) ? hh : v3[2];
		lastGx = ((hx - hs) + (hd - hz)) * 0.5F / 0.5F * sx;
		lastGz = ((hz - hs) + (hd - hx)) * 0.5F / 0.5F * sz;
		float h;
		if (shore && flat && !other && level - lo > 1.0e-4F) {
			float w = smooth ? Math.clamp(cover / count, 0.0F, 1.0F) : Math.clamp((hh - lo) / (level - lo), 0.0F, 1.0F);
			h = Math.min(lo + (w >= 0.5F || !smooth ? level - lo : SHORE_BELOW) * (2.0F * w - 1.0F), hiC);
		} else {
			h = Math.clamp(hh, lo, hiC);
		}
		h -= DROP * Math.clamp(Math.min(depthSum / shares, h - lo), 0, 1);
		return h;
	}

	static boolean wetAt(S t, float fl) { return t != null && wet(t) && Math.abs(floorOf(t) - fl) <= 0.05F; }

	/** Mirror of shoreQuad's corner for dry span a at (sx, sz): a wet sharer's own corner(), else under the ground; null: no quad. */
	static float shoreCorner(S a, int sx, int sz) {
		float fl = floorOf(a);
		int dirX = sx > 0 ? 0 : 1, dirZ = sz > 0 ? 2 : 3;
		S x = pick(a, dirX), z = pick(a, dirZ);
		if (wetAt(x, fl)) return corner(x, -sx, sz);
		if (wetAt(z, fl)) return corner(z, sx, -sz);
		S d = x != null ? pick(x, dirZ) : null;
		if (d == null && z != null) d = pick(z, dirX);
		if (wetAt(d, fl)) return corner(d, -sx, -sz);
		return fl - SHORE_BELOW;
	}

	static boolean nearWater(S a) {
		float fl = floorOf(a);
		for (int d = 0; d < 4; d++) if (wetAt(pick(a, d), fl)) return true;
		return false;
	}

	/**
	 * A flood front on flat ground running diagonally (cells with i + j < 24 wet, 0.4 deep, still): where the drawn surface crosses
	 * the ground. Returns {RMS distance of the waterline from the straight diagonal (blocks), shore quads that crack against a
	 * wet neighbor}.
	 */
	static double[] diagonalFront(boolean marching) {
		shore = marching;
		int C = 2;
		g = new HfTileGrid(0, 0, 40, 96, C, 4, 0.25F, 4, 16, 16);
		sim = new HfTiledSim(g, HfCpuTest.voxels((x, y, z) -> y < 64));
		int tn = g.tn;
		sim.addTile(0, 0, (l, c) -> l == 0 && (c % tn) + (c / tn) < 24 ? 0.4 : 0.0);
		float[][] h = new float[tn + 1][tn + 1];
		boolean[][] has = new boolean[tn + 1][tn + 1];
		int cracks = 0;
		int[][] sg = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
		for (int col = 0; col < g.pageSpans; col++) {
			S a = new S(0, 0, col);
			int cx = col % tn, cz = col / tn;
			boolean w = wet(a);
			if (!w && !(marching && nearWater(a))) continue;
			for (int[] k : sg) {
				int px = cx + (k[0] > 0 ? 1 : 0), pz = cz + (k[1] > 0 ? 1 : 0);
				float v = w ? corner(a, k[0], k[1]) : shoreCorner(a, k[0], k[1]);
				if (has[px][pz] && Math.abs(h[px][pz] - v) > 1.0e-4F) cracks++;
				h[px][pz] = has[px][pz] ? h[px][pz] : v;
				has[px][pz] = true;
			}
		}
		// Waterline: along each grid line between corners that both have a surface, where it crosses the ground (64); a corner with no
		// surface (outside the water and the shore) counts as the edge of the water.
		double sum = 0.0;
		int n = 0;
		float ground = 64.0F;
		for (int i = 0; i <= tn; i++) {
			for (int j = 0; j <= tn; j++) {
				for (int[] dd : new int[][]{{1, 0}, {0, 1}}) {
					int i2 = i + dd[0], j2 = j + dd[1];
					if (i2 > tn || j2 > tn || i + j < 14 || i + j > 34) continue;
					boolean in1 = has[i][j] && h[i][j] > ground, in2 = has[i2][j2] && h[i2][j2] > ground;
					if (in1 == in2) continue;
					double t;
					if (has[i][j] && has[i2][j2]) t = (h[i][j] - ground) / (h[i][j] - h[i2][j2]);
					else t = in1 ? 1.0 : 0.0;
					double px = (i + dd[0] * t) * 0.5, pz = (j + dd[1] * t) * 0.5;
					double dist = (px + pz - 12.0) / Math.sqrt(2.0);
					sum += dist * dist;
					n++;
				}
			}
		}
		shore = true;
		double rms = Math.sqrt(sum / Math.max(n, 1));
		double mean = 0.0;
		return new double[]{rms, cracks, n, mean};
	}

	/**
	 * A flood front spreading over flat ground (a 1.5-block strip 0.3 deep let go across a tile): where the drawn surface meets
	 * the ground along the middle row of corners, frame by frame for 8 s. Returns {largest jump of the waterline in one frame
	 * (blocks), frames it jumped more than 0.2 blocks, how far the front got (blocks)}.
	 */
	static double[] frontMotion(boolean smoothLine) {
		return frontMotion(smoothLine, false);
	}

	/** bank: ground a block higher from z = 8 on, so the row measured runs along the foot of a 1-block bank. */
	static double[] frontMotion(boolean smoothLine, boolean bank) {
		smooth = smoothLine;
		int C = 2;
		g = new HfTileGrid(0, 0, 0, 96, C, 4, 0.25F, 4, 16, 16);
		sim = new HfTiledSim(g, HfCpuTest.voxels((x, y, z) -> y < (bank && z >= 8 ? 65 : 64)));
		sim.maxNewTiles = 0;
		int tn = g.tn;
		sim.addTile(0, 0, (l, c) -> l == 0 && (c % tn) < 3 ? 0.3 : 0.0);
		double prev = Double.NaN, worst = 0.0, last = 0.0;
		int big = 0;
		int row = 15;
		for (int f = 0; f < 60 * 8; f++) {
			sim.frame();
			// Corners (+x, +z) of the cells in the row: the waterline is where they cross the ground going out.
			double line = Double.NaN;
			float before = Float.NaN;
			for (int cx = 0; cx < tn; cx++) {
				S a = new S(0, 0, row * tn + cx);
				float h = wet(a) ? corner(a, 1, 1) : nearWater(a) ? shoreCorner(a, 1, 1) : Float.NaN;
				float above = h - 64.0F;
				if (!Float.isNaN(before) && before > 0.0F && !(above > 0.0F)) {
					double t = Float.isNaN(above) ? 1.0 : before / (before - above);
					line = (cx + t) * 0.5;
					break;
				}
				before = above;
			}
			if (!Double.isNaN(line)) {
				if (!Double.isNaN(prev)) {
					double jump = Math.abs(line - prev);
					worst = Math.max(worst, jump);
					big += jump > 0.2 ? 1 : 0;
				}
				prev = line;
				last = line;
			}
		}
		smooth = true;
		return new double[]{worst, big, last};
	}

	/**
	 * A stream of 0.1 blocks^3/s down 1-block terraces (3 blocks wide): over the last 5 s, the share of span-frames pouring over a
	 * brink (outflow toward a lower floor above the drawn-film threshold) that are drawn wet (presence over 0.5), and their mean
	 * depth.
	 */
	static double[] brinkDrawn(boolean smoothLine) {
		smooth = smoothLine;
		int C = 2;
		g = new HfTileGrid(0, 0, 0, 96, C, 4, 0.25F, 4, 16, 16);
		sim = new HfTiledSim(g, HfCpuTest.voxels((x, y, z) -> y < 40 - Math.floorDiv(Math.max(x, 0), 3)));
		sim.maxNewTiles = 0;
		int tn = g.tn;
		sim.addTile(0, 0, (l, c) -> 0.0);
		float cellA = g.cell * g.cell;
		double drawn = 0, all = 0, depthSum = 0;
		for (int f = 0; f < 60 * 25; f++) {
			for (int z = 14; z < 18; z++) {
				sim.inject(0, z * tn + 1, 41.0F, 0.1F / 4.0F / 60.0F / cellA);
			}
			sim.frame();
			if (f < 60 * 20) continue;
			for (int c = 0; c < g.pageSpans; c++) {
				S a = new S(0, 0, c);
				S n = pick(a, 0);
				if (n == null || floorOf(n) >= floorOf(a) - 0.5F || outTo(a, 0) <= FLOW_DRAW) continue;
				all++;
				drawn += presence(a) > 0.5F ? 1 : 0;
				depthSum += depth(a);
			}
		}
		smooth = true;
		return new double[]{drawn / Math.max(all, 1), depthSum / Math.max(all, 1)};
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
	static final float CURTAIN_MIN = 0.0005F, REACH = 0.42F, GRAV = 9.81F;
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
		float p = presence(t);
		if (p < 1.0F) land = (fl - 0.05F) * (1 - p) + land * p;
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
		for (boolean bank : new boolean[]{false, true}) {
			double[] jumpy = frontMotion(false, bank);
			double[] fluid = frontMotion(true, bank);
			HfCpuTest.check(fluid[0] < 0.5 * jumpy[0] && fluid[1] < 0.25 * Math.max(jumpy[1], 1.0) && fluid[2] > 2.0, String.format(
				"flood front on flat ground%s: the waterline moves at most %.2f blocks in a frame, %d frames over 0.2 blocks, front at %.1f blocks (all-or-nothing cells: %.2f, %d frames, %.1f)",
				bank ? " along the foot of a 1-block bank" : "", fluid[0], (int) fluid[1], fluid[2], jumpy[0], (int) jumpy[1], jumpy[2]));
		}
		double[] brinkOld = brinkDrawn(false);
		double[] brinkNow = brinkDrawn(true);
		HfCpuTest.check(brinkNow[0] > 0.9 && brinkNow[0] > brinkOld[0], String.format(
			"small stream down terraces: %.0f%% of the cells pouring over a brink are drawn wet (before: %.0f%%), mean brink depth %.4f",
			100 * brinkNow[0], 100 * brinkOld[0], brinkNow[1]));
		double[] old = diagonalFront(false);
		double[] now = diagonalFront(true);
		HfCpuTest.check(now[1] == 0 && now[0] < 0.4 * old[0], String.format(
			"diagonal flood front on flat ground: waterline %.3f blocks RMS off the straight line with the marching-squares shore (%.3f with cell edges), %d shore corners that do not meet a wet cell's",
			now[0], old[0], (int) now[1]));
	}
}
