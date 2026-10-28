package me.skaffy.client.water;

import java.util.Arrays;

/**
 * Java mirror of the tiled GPU solver: hf_flux.comp / hf_depth.comp / hf_remap.comp over span and flux pages, with neighbor
 * resolution through {@link HfTileGrid}, and the per-frame scheduling HfDomain and WaterWorld do (block edits, growth,
 * sleeping, flux pages). If you change the shaders or that scheduling, change this the same way.
 */
final class HfTiledSim {
	/** Fills a new tile: initial depth for (layer, column). */
	interface Fill {
		double depth(int layer, int col);
	}

	final HfTileGrid g;
	final HfTopology.Voxels vox;
	final int ps;
	final int[] geo;
	final float[] depth;
	final int[] links;
	final float[] flux;
	/** Mirror of the Air buffer (hf_depth.comp): per span page span volume in flight, seconds falling in, mean drop. */
	final float[] air;
	/** Churn per span from the last depth pass (blocks^2/s: inflow x head lost / cell area), for calibration and tests. */
	final float[] churn;
	static float AIR_MIN_DROP = 1.5F;
	/** Mirror of RUNNING_FLOW (hf_depth.comp): outflow that keeps a tile awake. */
	static final float RUNNING_FLOW = 0.0005F, RUNNING_SPEED = 0.15F;
	/** Mirror of the Air layout and the drawn-film switch (hf_common.glsl). */
	static final int AIR_STRIDE = 4;
	static final float FILM_DRY = 0.008F, FILM_DRAW = 0.0005F, FILM_SWITCH = 0.3F, FILM_FULL = 0.016F, STILL_FADE = 0.01F;
	float renderDepth = 0.035F;
	/** false: films switch frame by frame, as before the switch existed (for comparison). */
	static boolean filmSwitch = true;
	static final float AIR_TERMINAL = 12.0F;
	static final float AIR_G = 9.81F;
	/** Mirror of the foam buffer: two halves of one float per flux-page span, swapped every step. */
	final float[] foam;
	final int foamHalf;
	int foamRead;
	float foamLife = 1.5F;
	/** Bodies stirring the water, 12 floats each in the Globals entity layout (see HfWorld.stir and hfStir). */
	float[] stir = new float[0];
	float stirRate = 8.0F;
	float sleepRate = 0.002F;
	float sleepSeconds = 1.0F;
	float filmDepth = 0.1F;
	float filmRate = 0.02F;
	int maxNewTiles = 2;
	int[] pages = new int[0];

	HfTiledSim(HfTileGrid g, HfTopology.Voxels vox) {
		this.g = g;
		this.vox = vox;
		this.ps = g.pageSpans;
		this.geo = new int[g.maxPages * this.ps];
		this.depth = new float[g.maxPages * this.ps];
		this.links = new int[g.maxPages * this.ps];
		this.air = new float[g.maxPages * this.ps * AIR_STRIDE];
		this.churn = new float[g.maxPages * this.ps];
		this.flux = new float[g.maxFluxPages * this.ps * 8];
		this.foamHalf = g.maxFluxPages * this.ps;
		this.foam = new float[2 * this.foamHalf];
	}

	/** Mirror of hfStir: {vx, vz, height filled} for a column. */
	float[] stirAt(int tile, int col, float floorY, float surfaceY) {
		float[] r = new float[3];
		int tn = this.g.tn;
		float px = (this.g.tileX[tile] * tn + col % tn + 0.5F) / this.g.cpb;
		float pz = (this.g.tileZ[tile] * tn + col / tn + 0.5F) / this.g.cpb;
		for (int e = 0; e + 12 <= this.stir.length; e += 12) {
			float[] b = this.stir;
			float dx = px - 0.5F * (b[e] + b[e + 4]);
			float dz = pz - 0.5F * (b[e + 2] + b[e + 6]);
			float dist = (float) Math.sqrt(dx * dx + dz * dz);
			float radius = 0.5F * Math.max(b[e + 4] - b[e], b[e + 6] - b[e + 2]);
			float w = 1.0F - smoothstep(radius, radius + 0.35F, dist);
			float sub = Math.min(b[e + 5], surfaceY) - Math.max(b[e + 1], floorY);
			if (w <= 0.0F || sub <= 0.0F) {
				continue;
			}
			w *= Math.clamp(1.0F - (surfaceY - b[e + 5]) / 1.5F, 0.0F, 1.0F);
			float vx = b[e + 8];
			float vz = b[e + 10];
			if (dist > 0.05F) {
				vx += dx / dist * b[e + 3];
				vz += dz / dist * b[e + 3];
			}
			r[0] += vx * w * b[e + 7];
			r[1] += vz * w * b[e + 7];
			r[2] = Math.max(r[2], sub);
		}
		return r;
	}

	static float smoothstep(float a, float b, float x) {
		float t = Math.clamp((x - a) / (b - a), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	/** Foam of (tile, layer, col) as the last step wrote it, 0 when the tile is not simulated. */
	float foamAt(int tile, int layer, int col) {
		int fp = this.g.fluxPage[tile * 4 + layer];
		return fp < 0 ? 0.0F : this.foam[this.foamRead * this.foamHalf + fp * this.ps + col];
	}

	private void writePages(int tile, HfTileGrid.Tile t) {
		for (int l = 0; l < t.layersUsed; l++) {
			int base = this.g.spanPage[tile * 4 + l] * this.ps;
			System.arraycopy(t.geo, l * this.ps, this.geo, base, this.ps);
			System.arraycopy(t.links, l * this.ps, this.links, base, this.ps);
		}
	}

	/** Mirror of hfFallTime (hf_common.glsl). */
	static float fallTime(float h) {
		double a = AIR_G * Math.max(h, 0.0F) / (AIR_TERMINAL * AIR_TERMINAL);
		return (float) (AIR_TERMINAL / AIR_G * (a + Math.log(1.0 + Math.sqrt(Math.max(1.0 - Math.exp(-2.0 * a), 0.0)))));
	}

	/** Mirror of hfFallDistance. */
	static float fallDistance(float t) {
		double b = AIR_G * Math.max(t, 0.0F) / AIR_TERMINAL;
		return (float) (AIR_TERMINAL * AIR_TERMINAL / AIR_G * (b + Math.log(1.0 + Math.exp(-2.0 * b)) - Math.log(2.0)));
	}

	/** Builds and adds a tile; returns the tile index or -1 when the pool is full. */
	int addTile(int tx, int tz, Fill fill) {
		HfTileGrid.Tile t = this.g.build(this.vox, tx, tz);
		int tile = this.g.add(tx, tz, t);
		if (tile < 0) {
			return tile;
		}
		this.writePages(tile, t);
		for (int l = 0; l < t.layersUsed; l++) {
			int base = this.g.spanPage[tile * 4 + l] * this.ps;
			java.util.Arrays.fill(this.air, base * 3, (base + this.ps) * 3, 0.0F);
		}
		double vol = 0.0;
		float md = 0.0F;
		for (int l = 0; l < t.layersUsed; l++) {
			int base = this.g.spanPage[tile * 4 + l] * this.ps;
			for (int c = 0; c < this.ps; c++) {
				float d = fill != null && HfTileGrid.validGeo(t.geo[l * this.ps + c]) ? (float) fill.depth(l, c) : 0.0F;
				this.depth[base + c] = d;
				vol += (double) d * this.g.cell * this.g.cell;
				md = Math.max(md, d);
			}
		}
		this.g.volume[tile] = vol;
		this.g.maxDepth[tile] = md;
		return tile;
	}

	/** Mirror of hf_remap.comp for one tile; fluxPages: the flux pages the tile kept (NONE where none). */
	private void remap(int[] oldPages, int[] newPages, int[] fluxPages) {
		for (int col = 0; col < this.ps; col++) {
			float[] acc = new float[4];
			int[] src = {-1, -1, -1, -1};
			float[] srcAmount = new float[4];
			float[] shown = new float[4];
			for (int i = 0; i < 4; i++) {
				if (oldPages[i] < 0) {
					continue;
				}
				int s = oldPages[i] * this.ps + col;
				shown[i] = this.air[s * AIR_STRIDE + 3];
				int gg = this.geo[s];
				float d = this.depth[s];
				float inAir = this.air[s * AIR_STRIDE];
				if (HfTileGrid.validGeo(gg) && inAir > 0.0F) {
					float of = this.g.floorOf(gg);
					float oc = this.g.ceilOf(gg);
					int into = -1;
					float most = 0.0F;
					for (int j = 0; j < 4; j++) {
						if (newPages[j] < 0 || !HfTileGrid.validGeo(this.geo[newPages[j] * this.ps + col])) {
							continue;
						}
						int ng = this.geo[newPages[j] * this.ps + col];
						float nf = this.g.floorOf(ng);
						float nc = this.g.ceilOf(ng);
						if (nf <= of + 0.01F && of + 0.01F < nc) {
							into = j;
							break;
						}
						float overlap = Math.max(Math.min(oc, nc) - Math.max(of, nf), 0.0F);
						if (overlap > most) {
							into = j;
							most = overlap;
						}
					}
					if (into >= 0) {
						acc[into] += inAir / (this.g.cell * this.g.cell);
					}
				}
				if (!HfTileGrid.validGeo(gg) || d <= 0.0F) {
					continue;
				}
				float lo = this.g.floorOf(gg);
				float room = Math.max(this.g.ceilOf(gg) - lo, 0.0F);
				float hi = lo + Math.min(d, room);
				float pressure = Math.max(d - room, 0.0F);
				int best = -1;
				float bestOverlap = 0.0F;
				for (int j = 0; j < 4; j++) {
					if (newPages[j] < 0) {
						continue;
					}
					int ng = this.geo[newPages[j] * this.ps + col];
					if (!HfTileGrid.validGeo(ng)) {
						continue;
					}
					float nf = this.g.floorOf(ng);
					float nc = this.g.ceilOf(ng);
					float overlap = Math.max(Math.min(hi, nc) - Math.max(lo, nf), 0.0F);
					acc[j] += overlap;
					if (overlap > bestOverlap) {
						best = j;
						bestOverlap = overlap;
					}
					if (overlap > srcAmount[j]) {
						srcAmount[j] = overlap;
						src[j] = i;
					}
				}
				if (best >= 0) {
					acc[best] += pressure;
				}
			}
			for (int j = 0; j < 4; j++) {
				if (newPages[j] >= 0) {
					int ns = newPages[j] * this.ps + col;
					this.depth[ns] = acc[j];
					this.air[ns * AIR_STRIDE] = 0.0F;
					this.air[ns * AIR_STRIDE + 1] = 0.0F;
					this.air[ns * AIR_STRIDE + 2] = 0.0F;
					this.air[ns * AIR_STRIDE + 3] = src[j] >= 0 ? shown[src[j]] : 0.0F;
				}
			}
			float[][] f = new float[4][8];
			float[][] fo = new float[4][2];
			for (int i = 0; i < 4; i++) {
				if (fluxPages[i] < 0) {
					continue;
				}
				int base = fluxPages[i] * this.ps + col;
				System.arraycopy(this.flux, base * 8, f[i], 0, 8);
				fo[i][0] = this.foam[base];
				fo[i][1] = this.foam[this.foamHalf + base];
			}
			for (int j = 0; j < 4; j++) {
				if (fluxPages[j] < 0) {
					continue;
				}
				int base = fluxPages[j] * this.ps + col;
				int from = src[j];
				for (int k = 0; k < 8; k++) {
					this.flux[base * 8 + k] = from >= 0 ? f[from][k] : 0.0F;
				}
				this.foam[base] = from >= 0 ? fo[from][0] : 0.0F;
				this.foam[this.foamHalf + base] = from >= 0 ? fo[from][1] : 0.0F;
			}
		}
	}

	private int span(int tile, int layer, int col) {
		return this.g.spanPage[tile * 4 + layer] * this.ps + col;
	}

	private int fluxBase(int tile, int layer, int col) {
		return (this.g.fluxPage[tile * 4 + layer] * this.ps + col) * 8;
	}

	/** Neighbor through link nibble nib in slot k, as {tile, layer, col}, or null (mirror of hfNeighbor). */
	private int[] neighbor(int tile, int col, int k, int nib, boolean requireActive) {
		int tn = this.g.tn;
		int d = k >> 1;
		int cx = col % tn + HfTileGrid.DX[d];
		int cz = col / tn + HfTileGrid.DZ[d];
		int t = tile;
		if (cx < 0 || cz < 0 || cx >= tn || cz >= tn) {
			t = this.g.nbr[tile * 4 + d];
			if (t < 0) {
				return null;
			}
			cx = (cx + tn) % tn;
			cz = (cz + tn) % tn;
		}
		if (requireActive && !this.g.active[t]) {
			return null;
		}
		int layer = nib & 3;
		if (this.g.spanPage[t * 4 + layer] < 0) {
			return null;
		}
		return new int[]{t, layer, cz * tn + cx};
	}

	private static int rev(int k, int nib) {
		return ((k >> 1) ^ 1) * 2 + (nib >> 2);
	}

	void fluxPass(float dt, float keep) {
		float cell = this.g.cell;
		for (int e : this.pages) {
			int tile = e >> 2;
			int layer = e & 3;
			for (int col = 0; col < this.ps; col++) {
				int s = this.span(tile, layer, col);
				int base = this.fluxBase(tile, layer, col);
				int ga = this.geo[s];
				float hA = this.depth[s];
				if (!HfTileGrid.validGeo(ga) || hA <= 0.0F) {
					Arrays.fill(this.flux, base, base + 8, 0.0F);
					continue;
				}
				float fa = this.g.floorOf(ga);
				float ca = this.g.ceilOf(ga);
				float headA = fa + hA;
				float filmCarry = Math.clamp(hA / HfCpuTest.MIN_FLOW, 0.0F, 1.0F);
				float[] st = this.stirAt(tile, col, fa, Math.min(headA, ca));
				float drag = 1.0F - (float) Math.exp(-this.stirRate * dt);
				float[] out = new float[8];
				float total = 0.0F;
				for (int k = 0; k < 8; k++) {
					int nib = HfTileGrid.link(this.links[s], k);
					if (nib == HfTileGrid.LINK_NONE) {
						continue;
					}
					int[] nb = this.neighbor(tile, col, k, nib, true);
					if (nb == null) {
						continue;
					}
					int b = this.span(nb[0], nb[1], nb[2]);
					int gb = this.geo[b];
					float fb = this.g.floorOf(gb);
					float hB = this.depth[b];
					float headB = fb + hB;
					float lo = Math.max(fa, fb);
					float hi = Math.min(ca, this.g.ceilOf(gb));
					float wetA = Math.clamp(headA, lo, hi) - lo;
					if (wetA <= 0.0F) {
						continue;
					}
					float wetB = Math.clamp(headB, lo, hi) - lo;
					float area = cell * Math.max(wetA, wetB);
					// Over an edge onto lower water (a free overfall) only the water above the edge drives the flow (see hf_flux.comp).
					float drive = headA - Math.max(headB, fa);
					float carry = 1.0F;
					if (headB >= fa) {
						float m = HfCpuTest.MIN_FLOW;
						drive = fa + Math.max(hA - m, 0.0F) - Math.max(fb + Math.max(hB - m, 0.0F), fa);
						carry = filmCarry;
					}
					float q = this.flux[base + k] * keep * carry + dt * 9.81F * area * drive / cell;
					if (st[2] > 0.0F) {
						int d = k >> 1;
						float target = (st[0] * HfTileGrid.DX[d] + st[1] * HfTileGrid.DZ[d]) * cell * Math.min(st[2], wetA);
						q += drag * Math.max(target - q, 0.0F);
					}
					out[k] = Math.max(q, 0.0F);
					total += out[k];
				}
				float volume = hA * cell * cell;
				float scale = total * dt > volume ? volume / (total * dt) : 1.0F;
				for (int k = 0; k < 8; k++) {
					this.flux[base + k] = out[k] * scale;
				}
			}
		}
	}

	void depthPass(float dt) {
		float cell = this.g.cell;
		int read = this.foamRead * this.foamHalf;
		int write = (1 - this.foamRead) * this.foamHalf;
		this.foamRead ^= 1;
		float foamKeep = (float) Math.exp(-dt / Math.max(this.foamLife, 0.05F));
		for (int tile : this.g.activeList) {
			this.g.volume[tile] = 0.0;
			this.g.maxDepth[tile] = 0.0F;
			this.g.maxSpeed[tile] = 0.0F;
			this.g.maxRate[tile] = 0.0F;
			this.g.border[tile] = 0;
		}
		for (int e : this.pages) {
			int tile = e >> 2;
			int layer = e & 3;
			for (int col = 0; col < this.ps; col++) {
				int s = this.span(tile, layer, col);
				if (!HfTileGrid.validGeo(this.geo[s])) {
					continue;
				}
				int base = this.fluxBase(tile, layer, col);
				float in = 0.0F;
				float out = 0.0F;
				float peak = 0.0F;
				float old = this.depth[s];
				float floorA = this.g.floorOf(this.geo[s]);
				float foamA = this.foam[read + base / 8];
				float foamIn = 0.0F;
				float falling = 0.0F;
				float airIn = 0.0F;
				float airDrop = 0.0F;
				float churnSum = 0.0F;
				float outSum = 0.0F;
				for (int k = 0; k < 8; k++) {
					outSum += this.flux[base + k];
				}
				// Our water's speed after the step is not known yet; the outflow over the old depth is close enough.
				float ua = outSum / (cell * Math.max(old, 0.05F));
				for (int k = 0; k < 8; k++) {
					int nib = HfTileGrid.link(this.links[s], k);
					if (nib == HfTileGrid.LINK_NONE) {
						continue;
					}
					int[] nb = this.neighbor(tile, col, k, nib, true);
					if (nb == null) {
						continue;
					}
					float o = this.flux[base + k];
					int nbBase = this.fluxBase(nb[0], nb[1], nb[2]);
					float fi = this.flux[nbBase + rev(k, nib)];
					out += o;
					in += fi;
					peak = Math.max(peak, Math.max(o, fi));
					if (fi > 0.0F) {
						foamIn += fi * this.foam[read + nbBase / 8];
						float drop = this.g.floorOf(this.geo[this.span(nb[0], nb[1], nb[2])]) - (floorA + old);
						if (drop > AIR_MIN_DROP) {
							airIn += fi;
							airDrop += fi * drop;
						} else if (drop > 0.0F) {
							falling += fi * Math.min(drop, 4.0F);
						} else {
							int bs = this.span(nb[0], nb[1], nb[2]);
							float hb = this.depth[bs];
							float ub = fi / (cell * Math.max(hb, 0.05F));
							float loss = this.g.floorOf(this.geo[bs]) + hb - (floorA + old) + (ub * ub - ua * ua) / (2.0F * AIR_G);
							churnSum += fi * Math.clamp(loss, 0.0F, 4.0F);
						}
					}
				}
				float area = cell * cell;
				float airVol = this.air[s * AIR_STRIDE];
				float airT = this.air[s * AIR_STRIDE + 1];
				float airH = this.air[s * AIR_STRIDE + 2];
				if (airIn > 0.0F) {
					airH = airDrop / airIn;
					airVol += airIn * dt;
				}
				float landed = 0.0F;
				if (airVol > 0.0F) {
					float tf = fallTime(airH);
					airT = Math.min(airT + dt, tf + 1000.0F);
					if (airT >= tf) {
						landed = airVol * (float) (1.0 - Math.exp(-dt / Math.max(tf, 0.05F)));
						if (airVol - landed < 1.0e-6F) {
							landed = airVol;
						}
						airVol -= landed;
					}
				}
				if (airVol <= 0.0F) {
					airVol = 0.0F;
					airT = 0.0F;
				}
				this.air[s * AIR_STRIDE] = airVol;
				this.air[s * AIR_STRIDE + 1] = airT;
				this.air[s * AIR_STRIDE + 2] = airH;
				falling += landed / dt * Math.min(airH, 4.0F);
				float nh = Math.max(old + (dt * (in - airIn - out) + landed) / area, 0.0F);
				this.depth[s] = nh;
				{
					float sv = this.air[s * AIR_STRIDE + 3];
					boolean on = sv >= 2.0F;
					float shown = sv - (on ? 2.0F : 0.0F);
					on = Math.max(out, in - airIn + landed / dt) > FILM_DRAW * (on && filmSwitch ? 0.4F : 1.0F);
					float still = Math.clamp((nh - this.renderDepth) / STILL_FADE, 0.0F, 1.0F);
					float film = on && nh > 0.0005F ? Math.max(Math.clamp((nh - FILM_DRY) / (FILM_FULL - FILM_DRY), 0.0F, 1.0F), Math.clamp(out / (4.0F * FILM_DRAW), 0.0F, 1.0F)) : 0.0F;
					float target = Math.max(still, film);
					float stepS = dt / FILM_SWITCH;
					shown = !filmSwitch ? target : target > shown ? Math.min(target, shown + stepS) : Math.max(target, shown - stepS);
					this.air[s * AIR_STRIDE + 3] = shown + (on ? 2.0F : 0.0F);
				}
				this.churn[s] = churnSum / area;
				float speed = nh > 0.05F ? Math.min(peak / (cell * nh), 30.0F) : 0.0F;
				float mass = foamA * old + dt * (foamIn - out * foamA) / area;
				float f = nh > 1.0e-4F ? Math.clamp(mass / nh, 0.0F, 1.0F) * foamKeep : 0.0F;
				f = Math.max(f, 0.9F * smoothstep(0.0F, 6.0F, falling / area));
				f = Math.max(f, 0.35F * smoothstep(5.0F, 12.0F, speed) * smoothstep(0.05F, 0.2F, nh));
				f = Math.max(f, 0.85F * smoothstep(1.5F, 12.0F, churnSum / area) * smoothstep(0.02F, 0.1F, nh));
				float[] st = this.stirAt(tile, col, floorA, floorA + nh);
				if (st[2] > 0.0F) {
					f = Math.max(f, 0.35F * smoothstep(1.5F, 4.0F, (float) Math.hypot(st[0], st[1])));
				}
				this.foam[write + base / 8] = nh > 0.0F ? f : 0.0F;
				this.g.volume[tile] += (double) nh * cell * cell + airVol;
				this.g.maxDepth[tile] = Math.max(this.g.maxDepth[tile], nh);
				if (nh > 0.05F) {
					this.g.maxSpeed[tile] = Math.max(this.g.maxSpeed[tile], Math.min(peak / (cell * nh), 30.0F));
				}
				boolean busy = airVol > 1.0e-5F || (out > RUNNING_FLOW && out > RUNNING_SPEED * cell * Math.max(nh, 0.008F));
				this.g.maxRate[tile] = Math.max(this.g.maxRate[tile], Math.max(Math.abs(nh - old) / dt, busy ? 1.0F : 0.0F));
				this.g.border[tile] |= this.growthBits(tile, s, col, nh);
			}
		}
	}

	private int growthBits(int tile, int s, int col, float h) {
		if (h <= HfCpuTest.MIN_FLOW) {
			return 0;
		}
		int bits = 0;
		for (int d = 0; d < 4; d++) {
			int cx = col % this.g.tn + HfTileGrid.DX[d];
			int cz = col / this.g.tn + HfTileGrid.DZ[d];
			boolean outside = cx < 0 || cz < 0 || cx >= this.g.tn || cz >= this.g.tn;
			if (!outside || this.g.nbr[tile * 4 + d] >= 0) {
				continue;
			}
			if (HfTileGrid.link(this.links[s], d * 2) != HfTileGrid.LINK_NONE || HfTileGrid.link(this.links[s], d * 2 + 1) != HfTileGrid.LINK_NONE) {
				bits |= 1 << d;
			}
		}
		return bits;
	}

	/** Mirror of hf_reservoir.comp. */
	void applyReservoirs() {
		for (float[] r : this.g.reservoirs()) {
			int tile = (int) r[0];
			int col = (int) r[1];
			float level = r[2];
			if (!this.g.active[tile]) {
				continue;
			}
			for (int l = 0; l < 4; l++) {
				int page = this.g.spanPage[tile * 4 + l];
				if (page < 0) {
					continue;
				}
				int s = page * this.ps + col;
				int gg = this.geo[s];
				if (!HfTileGrid.validGeo(gg)) {
					continue;
				}
				float fl = this.g.floorOf(gg);
				if (!(fl < level && level <= this.g.ceilOf(gg))) {
					continue;
				}
				float target = level - fl;
				this.depth[s] = r[3] == HfTileGrid.RESERVOIR_FIXED ? target : Math.max(this.depth[s], target);
				break;
			}
		}
	}

	/** Mirror of HfDomain.inject + hf_inject.comp (applied immediately here). */
	boolean inject(int tile, int col, float y, float add) {
		int layer = this.g.layerAt(tile, col, y);
		if (layer < 0) {
			return false;
		}
		int s = this.span(tile, layer, col);
		this.depth[s] = Math.max(this.depth[s] + add, 0.0F);
		this.g.quiet[tile] = 0.0F;
		return true;
	}

	/** Depth of (tile, layer, col), or 0 when the tile or layer does not exist. */
	float depthAt(int tile, int layer, int col) {
		if (tile < 0 || this.g.spanPage[tile * 4 + layer] < 0) {
			return 0.0F;
		}
		return this.depth[this.span(tile, layer, col)];
	}

	double volume() {
		double v = 0.0;
		for (int tile = 0; tile < this.g.count; tile++) {
			for (int l = 0; l < this.g.layersUsed[tile]; l++) {
				int base = this.g.spanPage[tile * 4 + l] * this.ps;
				for (int c = 0; c < this.ps; c++) {
					if (HfTileGrid.validGeo(this.geo[base + c])) {
						v += (double) this.depth[base + c] * this.g.cell * this.g.cell + this.air[(base + c) * AIR_STRIDE];
					}
				}
			}
		}
		return v;
	}

	/** One 1/60 s frame the way WaterWorld + HfDomain run it. Returns the number of solver steps taken. */
	int frame() {
		float frameDt = 1.0F / 60.0F;
		for (int tile : this.g.takeDirty()) {
			HfTileGrid.Tile t = this.g.build(this.vox, this.g.tileX[tile], this.g.tileZ[tile]);
			int[] old = this.g.replace(tile, t);
			if (old == null) {
				continue;
			}
			this.writePages(tile, t);
			for (int p : this.g.lastFreshFlux) {
				Arrays.fill(this.flux, p * this.ps * 8, (p + 1) * this.ps * 8, 0.0F);
				Arrays.fill(this.foam, p * this.ps, (p + 1) * this.ps, 0.0F);
				Arrays.fill(this.foam, this.foamHalf + p * this.ps, this.foamHalf + (p + 1) * this.ps, 0.0F);
			}
			this.remap(old, Arrays.copyOfRange(this.g.spanPage, tile * 4, tile * 4 + 4), Arrays.copyOfRange(this.g.fluxPage, tile * 4, tile * 4 + 4));
			this.g.releaseSpanPages(old);
		}
		for (int[] req : this.g.growthRequests(this.maxNewTiles)) {
			this.addTile(req[0], req[1], null);
		}
		HfTileGrid.Activation act = this.g.updateActive(frameDt, this.sleepRate, this.sleepSeconds, this.filmDepth, this.filmRate);
		for (int p : act.newFluxPages) {
			Arrays.fill(this.flux, p * this.ps * 8, (p + 1) * this.ps * 8, 0.0F);
			Arrays.fill(this.foam, p * this.ps, (p + 1) * this.ps, 0.0F);
			Arrays.fill(this.foam, this.foamHalf + p * this.ps, this.foamHalf + (p + 1) * this.ps, 0.0F);
		}
		this.pages = act.pages;
		if (this.pages.length == 0) {
			return 0;
		}
		float wave = (float) Math.sqrt(9.81F * Math.max(this.g.activeMax(this.g.maxDepth), 0.01F)) + this.g.activeMax(this.g.maxSpeed);
		float stable = 0.3F * this.g.cell / wave;
		int steps = Math.clamp((int) Math.ceil(frameDt / stable), 1, 8);
		float dt = Math.min(frameDt / steps, stable);
		float keep = (float) Math.pow(0.995, dt * 60.0) / (1.0F + 0.2F * dt);
		for (int i = 0; i < steps; i++) {
			this.fluxPass(dt, keep);
			this.depthPass(dt);
			this.applyReservoirs();
		}
		return steps;
	}
}
