package me.skaffy.client.water;

import java.util.Arrays;

/**
 * Java mirror of the tiled GPU solver: hf_flux.comp / hf_depth.comp over a tile list, with neighbor resolution through
 * {@link HfTileGrid}, and the per-frame scheduling HfDomain does (growth, sleeping, zeroing the flux of tiles that leave the
 * dispatch set). If you change the shaders or HfDomain.frame, change this the same way.
 */
final class HfTiledSim {
	final HfTileGrid g;
	final HfTopology.Voxels vox;
	final float[] floor;
	final float[] ceil;
	final float[] h;
	final float[] flux;
	final int[] links;
	float sleepRate = 0.002F;
	float sleepSeconds = 1.0F;
	float filmDepth = 0.1F;
	float filmRate = 0.02F;
	int maxNewTiles = 2;
	int frames;

	HfTiledSim(HfTileGrid g, HfTopology.Voxels vox) {
		this.g = g;
		this.vox = vox;
		int n = g.maxTiles * g.tileSpans;
		this.floor = new float[n];
		this.ceil = new float[n];
		this.h = new float[n];
		this.flux = new float[n * 8];
		this.links = new int[n * 2];
		Arrays.fill(this.floor, 1.0e30F);
		Arrays.fill(this.ceil, -1.0e30F);
		Arrays.fill(this.links, -1);
	}

	/** Builds and adds a tile; {@code fill} gives the initial depth per local span (or null for dry). Returns the slot or -1. */
	int addTile(int tx, int tz, java.util.function.IntToDoubleFunction fill) {
		HfTileGrid.Tile t = this.g.build(this.vox, tx, tz);
		int slot = this.g.add(tx, tz, t.dropped);
		if (slot < 0) {
			return slot;
		}
		int base = slot * this.g.tileSpans;
		double vol = 0.0;
		float md = 0.0F;
		for (int i = 0; i < this.g.tileSpans; i++) {
			this.floor[base + i] = t.floor[i];
			this.ceil[base + i] = t.ceil[i];
			float d = fill != null && t.valid(i) ? (float) fill.applyAsDouble(i) : 0.0F;
			this.h[base + i] = d;
			vol += (double) d * this.g.cell * this.g.cell;
			md = Math.max(md, d);
		}
		System.arraycopy(t.links, 0, this.links, base * 2, this.g.tileSpans * 2);
		Arrays.fill(this.flux, base * 8, (base + this.g.tileSpans) * 8, 0.0F);
		this.g.volume[slot] = vol;
		this.g.maxDepth[slot] = md;
		return slot;
	}

	private boolean valid(int s) {
		return this.ceil[s] > this.floor[s];
	}

	private int link(int s, int k) {
		return HfTileGrid.link(this.links, s, k);
	}

	private int columnX(int s) {
		return (s % this.g.tileSpans) / this.g.layers % this.g.tn;
	}

	private int columnZ(int s) {
		return (s % this.g.tileSpans) / this.g.layers / this.g.tn;
	}

	int neighbor(int s, int k, int l, boolean requireActive) {
		int ts = this.g.tileSpans;
		int tn = this.g.tn;
		int slot = s / ts;
		int d = k >> 1;
		int cx = this.columnX(s) + HfTileGrid.DX[d];
		int cz = this.columnZ(s) + HfTileGrid.DZ[d];
		int ns = slot;
		if (cx < 0 || cz < 0 || cx >= tn || cz >= tn) {
			ns = this.g.nbr[slot * 4 + d];
			if (ns < 0) {
				return -1;
			}
			cx = (cx + tn) % tn;
			cz = (cz + tn) % tn;
		}
		if (requireActive && !this.g.active[ns]) {
			return -1;
		}
		return ns * ts + (cz * tn + cx) * this.g.layers + (l >> 3);
	}

	void fluxPass(float dt, float keep) {
		float cell = this.g.cell;
		for (int slot : this.g.activeList) {
			for (int i = 0; i < this.g.tileSpans; i++) {
				int s = slot * this.g.tileSpans + i;
				int base = s * 8;
				float hA = this.h[s];
				if (!this.valid(s) || hA <= HfCpuTest.MIN_FLOW) {
					Arrays.fill(this.flux, base, base + 8, 0.0F);
					continue;
				}
				float headA = this.floor[s] + hA;
				float[] out = new float[8];
				float total = 0.0F;
				for (int k = 0; k < 8; k++) {
					int l = this.link(s, k);
					if (l == HfTileGrid.LINK_NONE) {
						continue;
					}
					int b = this.neighbor(s, k, l, true);
					if (b < 0) {
						continue;
					}
					float headB = this.floor[b] + this.h[b];
					float lo = Math.max(this.floor[s], this.floor[b]);
					float hi = Math.min(this.ceil[s], this.ceil[b]);
					float wetA = Math.clamp(headA, lo, hi) - lo;
					if (wetA <= 0.0F) {
						continue;
					}
					float wetB = Math.clamp(headB, lo, hi) - lo;
					float area = cell * Math.max(wetA, wetB);
					float q = this.flux[base + k] * keep + dt * 9.81F * area * (headA - headB) / cell;
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
		for (int slot : this.g.activeList) {
			double vol = 0.0;
			float md = 0.0F;
			float ms = 0.0F;
			float mr = 0.0F;
			int bits = 0;
			for (int i = 0; i < this.g.tileSpans; i++) {
				int s = slot * this.g.tileSpans + i;
				if (!this.valid(s)) {
					continue;
				}
				int base = s * 8;
				float in = 0.0F;
				float out = 0.0F;
				float peak = 0.0F;
				for (int k = 0; k < 8; k++) {
					int l = this.link(s, k);
					if (l == HfTileGrid.LINK_NONE) {
						continue;
					}
					int t = this.neighbor(s, k, l, true);
					if (t < 0) {
						continue;
					}
					float o = this.flux[base + k];
					float fi = this.flux[t * 8 + (l & 7)];
					out += o;
					in += fi;
					peak = Math.max(peak, Math.max(o, fi));
				}
				float old = this.h[s];
				float nh = Math.max(old + dt * (in - out) / (cell * cell), 0.0F);
				this.h[s] = nh;
				vol += (double) nh * cell * cell;
				md = Math.max(md, nh);
				if (nh > 0.05F) {
					ms = Math.max(ms, Math.min(nh > 0 ? peak / (cell * nh) : 0.0F, 30.0F));
				}
				mr = Math.max(mr, Math.abs(nh - old) / dt);
				bits |= this.growthBits(s, nh);
			}
			this.g.volume[slot] = vol;
			this.g.maxDepth[slot] = md;
			this.g.maxSpeed[slot] = ms;
			this.g.maxRate[slot] = mr;
			this.g.border[slot] = bits;
		}
	}

	private int growthBits(int s, float depth) {
		if (depth <= HfCpuTest.MIN_FLOW) {
			return 0;
		}
		int slot = s / this.g.tileSpans;
		int bits = 0;
		for (int d = 0; d < 4; d++) {
			int cx = this.columnX(s) + HfTileGrid.DX[d];
			int cz = this.columnZ(s) + HfTileGrid.DZ[d];
			boolean outside = cx < 0 || cz < 0 || cx >= this.g.tn || cz >= this.g.tn;
			if (!outside || this.g.nbr[slot * 4 + d] >= 0) {
				continue;
			}
			if (this.link(s, d * 2) != HfTileGrid.LINK_NONE || this.link(s, d * 2 + 1) != HfTileGrid.LINK_NONE) {
				bits |= 1 << d;
			}
		}
		return bits;
	}

	double volume() {
		double v = 0.0;
		for (int s = 0; s < this.g.count * this.g.tileSpans; s++) {
			if (this.valid(s)) {
				v += (double) this.h[s] * this.g.cell * this.g.cell;
			}
		}
		return v;
	}

	/** One 1/60 s frame the way HfDomain.frame runs it. Returns the number of solver steps taken. */
	int frame() {
		float frameDt = 1.0F / 60.0F;
		for (int[] req : this.g.growthRequests(this.maxNewTiles)) {
			this.addTile(req[0], req[1], null);
		}
		int[] left = this.g.updateActive(frameDt, this.sleepRate, this.sleepSeconds, this.filmDepth, this.filmRate);
		for (int slot : left) {
			int base = slot * this.g.tileSpans * 8;
			Arrays.fill(this.flux, base, base + this.g.tileSpans * 8, 0.0F);
		}
		this.frames++;
		if (this.g.activeList.length == 0) {
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
		}
		return steps;
	}
}
