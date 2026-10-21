package me.skaffy.client.water;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import me.skaffy.client.vk.ComputeProgram;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.Program;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * GPU side of the sparse heightfield (milestone 3): a pool of tile slots holding spans, links, fluxes and per-group stats,
 * plus the render mesh. {@link HfTileGrid} decides which slots exist and which are simulated; this class uploads new tiles,
 * records the flux, depth, mesh and probe dispatches over the chosen slots, and reads the stats back.
 *
 * <p>All heightfield shaders share one binding layout: 0 Globals UBO, 1 geo, 2 depth, 3 flux, 4 links, 5 tile list,
 * 6 tile info, 7 tile active flags, 8 stats, 9 verts, 10 quads, 11 mesh counters, 12 view state (probe only).
 */
final class HfDomain implements AutoCloseable {
	/** Two vec4 per (slot, group of 256 spans): (volume, max depth, max speed, max rate), (border bits, 0, 0, 0). */
	private static final int STATS_BYTES = 32;
	private static final int FLUX_PER_SPAN = HfTopology.LINKS * 4;

	private static ComputeProgram[] programs;

	private static ComputeProgram[] programs() {
		if (programs == null) {
			int U = Desc.UBO;
			int S = Desc.SSBO;
			int[] layout = {U, S, S, S, S, S, S, S, S, S, S, S, S};
			programs = new ComputeProgram[]{
				new ComputeProgram("hf_flux.comp", layout),
				new ComputeProgram("hf_depth.comp", layout),
				new ComputeProgram("hf_mesh.comp", layout),
				new ComputeProgram("hf_probe.comp", layout),
				new ComputeProgram("mesh_args.comp", U, S)
			};
		}
		return programs;
	}

	static void closePrograms() {
		if (programs != null) {
			for (ComputeProgram p : programs) {
				p.close();
			}
			programs = null;
		}
	}

	private record Pending(int slot, VkBuf staging) {
	}

	final HfTileGrid grid;
	final int originX;
	final int originZ;
	final int originY;
	final int cellsPerBlock;
	final float cell;
	final int quadCapacity;
	double initialVolume;

	private final int tileSpans;
	private final int groupsPerTile;
	private final VkBuf geo;
	private final VkBuf depth;
	private final VkBuf flux;
	private final VkBuf links;
	private final VkBuf tileInfo;
	private final VkBuf stats;
	private final VkBuf[] statsReadback = new VkBuf[Vk.FRAME_RING];
	private final int[][] statsSlots = new int[Vk.FRAME_RING][];
	private final VkBuf[] listRing = new VkBuf[Vk.FRAME_RING];
	private final VkBuf[] activeRing = new VkBuf[Vk.FRAME_RING];
	final VkBuf verts;
	final VkBuf quads;
	final VkBuf meshCounters;
	private final List<Pending> pending = new ArrayList<>();
	private boolean tileInfoDirty;
	private boolean statsCleared;

	int lastSteps;
	float lastDt;
	private boolean meshDirty = true;
	private float meshRenderDepth = Float.NaN;

	HfDomain(HfTileGrid grid) {
		this.grid = grid;
		this.originX = grid.originX;
		this.originZ = grid.originZ;
		this.originY = grid.yMin;
		this.cellsPerBlock = grid.cpb;
		this.cell = grid.cell;
		this.tileSpans = grid.tileSpans;
		this.groupsPerTile = grid.tileSpans / 256;
		long spans = (long) grid.maxTiles * grid.tileSpans;
		this.quadCapacity = (int) Math.clamp((long) grid.maxTiles * grid.tn * grid.tn * 3L / 2L, 65_536L, 4_000_000L);

		this.geo = VkBuf.device(spans * 8L, 0);
		this.depth = VkBuf.device(spans * 4L, 0);
		this.flux = VkBuf.device(spans * FLUX_PER_SPAN, 0);
		this.links = VkBuf.device(spans * 8L, 0);
		this.tileInfo = VkBuf.device((long) grid.maxTiles * 32L, 0);
		long statsBytes = (long) grid.maxTiles * this.groupsPerTile * STATS_BYTES;
		this.stats = VkBuf.device(statsBytes, 0);
		for (int i = 0; i < Vk.FRAME_RING; i++) {
			this.statsReadback[i] = VkBuf.readback(statsBytes);
			this.listRing[i] = VkBuf.upload((long) grid.maxTiles * 4L, VkBuf.STORAGE);
			this.activeRing[i] = VkBuf.upload((long) grid.maxTiles * 4L, VkBuf.STORAGE);
		}
		this.verts = VkBuf.device(this.quadCapacity * 4L * 16L, 0);
		this.quads = VkBuf.device(this.quadCapacity * 16L, 0);
		this.meshCounters = VkBuf.device(64, VkBuf.INDIRECT);
		programs();
	}

	/** Size of the GPU allocation, for /water hf stats. */
	long bytes() {
		return (long) this.grid.maxTiles * this.tileSpans * (8L + 4L + FLUX_PER_SPAN + 8L) + this.quadCapacity * 80L;
	}

	/**
	 * Puts a built tile into a pool slot; its data is uploaded at the start of the next {@link #record}.
	 *
	 * @param initialDepth depth per local span, or null for a dry tile
	 * @return the slot, or {@link HfTileGrid#NONE} when the pool is full
	 */
	int addTile(int tx, int tz, HfTileGrid.Tile t, float[] initialDepth) {
		int slot = this.grid.add(tx, tz, t.dropped);
		if (slot == HfTileGrid.NONE) {
			return slot;
		}
		int ts = this.tileSpans;
		VkBuf staging = VkBuf.upload(ts * 20L, 0);
		ByteBuffer b = staging.mapped();
		double volume = 0.0;
		float maxDepth = 0.0F;
		float area = this.cell * this.cell;
		for (int s = 0; s < ts; s++) {
			b.putFloat(s * 8, t.floor[s]).putFloat(s * 8 + 4, t.ceil[s]);
			float h = initialDepth != null && t.valid(s) ? initialDepth[s] : 0.0F;
			b.putFloat(ts * 8 + s * 4, h);
			volume += (double) h * area;
			maxDepth = Math.max(maxDepth, h);
		}
		for (int i = 0; i < ts * 2; i++) {
			b.putInt(ts * 12 + i * 4, t.links[i]);
		}
		staging.flush(0, ts * 20L);
		this.grid.volume[slot] = volume;
		this.grid.maxDepth[slot] = maxDepth;
		this.pending.add(new Pending(slot, staging));
		this.tileInfoDirty = true;
		this.meshDirty = true;
		return slot;
	}

	private void flushPending(VkCommandBuffer cb) {
		long ts = this.tileSpans;
		for (Pending p : this.pending) {
			long s0 = p.slot() * ts;
			p.staging().copyTo(cb, this.geo, 0, s0 * 8L, ts * 8L);
			p.staging().copyTo(cb, this.depth, ts * 8L, s0 * 4L, ts * 4L);
			p.staging().copyTo(cb, this.links, ts * 12L, s0 * 8L, ts * 8L);
			this.flux.fill(cb, s0 * FLUX_PER_SPAN, ts * FLUX_PER_SPAN, 0);
			Vk.destroyLater(p.staging());
		}
		this.pending.clear();
		if (this.tileInfoDirty) {
			this.tileInfoDirty = false;
			int n = this.grid.count;
			VkBuf staging = VkBuf.upload(n * 32L, 0);
			ByteBuffer b = staging.mapped();
			for (int s = 0; s < n; s++) {
				for (int d = 0; d < 4; d++) {
					b.putInt(s * 32 + d * 4, this.grid.nbr[s * 4 + d]);
				}
				b.putInt(s * 32 + 16, this.grid.tileX[s]).putInt(s * 32 + 20, this.grid.tileZ[s]).putInt(s * 32 + 24, 0).putInt(s * 32 + 28, 0);
			}
			staging.flush(0, n * 32L);
			staging.copyTo(cb, this.tileInfo, 0, 0, n * 32L);
			Vk.destroyLater(staging);
		}
	}

	/** Reads the stats this ring slot recorded {@link Vk#FRAME_RING} frames ago into the grid. Call once per frame before planning. */
	void readStats() {
		int ring = Vk.ringSlot();
		int[] slots = this.statsSlots[ring];
		if (slots == null) {
			return;
		}
		this.statsSlots[ring] = null;
		VkBuf rb = this.statsReadback[ring];
		rb.invalidate(0, (long) this.grid.count * this.groupsPerTile * STATS_BYTES);
		ByteBuffer b = rb.mapped();
		HfTileGrid g = this.grid;
		// Only slots that were simulated in that frame carry fresh numbers; the rest keep what the grid already has.
		for (int slot : slots) {
			double volume = 0.0;
			float maxDepth = 0.0F;
			float maxSpeed = 0.0F;
			float maxRate = 0.0F;
			int border = 0;
			for (int i = 0; i < this.groupsPerTile; i++) {
				int o = (slot * this.groupsPerTile + i) * STATS_BYTES;
				volume += b.getFloat(o);
				maxDepth = Math.max(maxDepth, b.getFloat(o + 4));
				maxSpeed = Math.max(maxSpeed, b.getFloat(o + 8));
				maxRate = Math.max(maxRate, b.getFloat(o + 12));
				border |= b.getInt(o + 16);
			}
			g.volume[slot] = volume;
			g.maxDepth[slot] = maxDepth;
			g.maxSpeed[slot] = maxSpeed;
			g.maxRate[slot] = maxRate;
			g.border[slot] = border;
		}
	}

	/** Largest stable time step for the simulated tiles (shallow-water CFL condition). */
	float maxStableDt() {
		float waveSpeed = (float) Math.sqrt(WaterSettings.gravity * Math.max(this.grid.activeMax(this.grid.maxDepth), 0.01F))
			+ this.grid.activeMax(this.grid.maxSpeed);
		return WaterSettings.hfCfl * this.cell / Math.max(waveSpeed, 1.0e-3F);
	}

	private int[] push(int count, float a, float b, float c, float d, float e, float f, float g, float h, int i, int j, int k) {
		return new int[]{
			this.grid.tn, this.grid.layers, this.tileSpans, count,
			Program.f(a), Program.f(b), Program.f(c), Program.f(d),
			Program.f(e), Program.f(f), Program.f(g), Program.f(h),
			i, j, k, 0
		};
	}

	private void bind(ComputeProgram p, VkCommandBuffer cb, Desc ubo, int ring, VkBuf view) {
		p.bind(cb, ubo, Desc.ssbo(this.geo), Desc.ssbo(this.depth), Desc.ssbo(this.flux), Desc.ssbo(this.links), Desc.ssbo(this.listRing[ring]),
			Desc.ssbo(this.tileInfo), Desc.ssbo(this.activeRing[ring]), Desc.ssbo(this.stats), Desc.ssbo(this.verts), Desc.ssbo(this.quads),
			Desc.ssbo(this.meshCounters), Desc.ssbo(view));
	}

	/**
	 * Uploads new tiles, zeroes the fluxes of tiles that left the dispatch set, and runs {@code steps} solver steps over the
	 * grid's active list. Call every frame (with steps = 0 while paused) so uploads and the per-frame tile lists stay current.
	 */
	void record(VkCommandBuffer cb, Desc ubo, int[] leftActive, int steps, float dt) {
		ComputeProgram[] p = programs();
		if (!this.statsCleared) {
			this.stats.fill(cb, 0);
			this.statsCleared = true;
		}
		this.flushPending(cb);
		long ts = this.tileSpans;
		for (int slot : leftActive) {
			this.flux.fill(cb, slot * ts * FLUX_PER_SPAN, ts * FLUX_PER_SPAN, 0);
		}
		Vk.barrier(cb);

		int ring = Vk.ringSlot();
		int[] list = this.grid.activeList;
		ByteBuffer lb = this.listRing[ring].mapped();
		ByteBuffer ab = this.activeRing[ring].mapped();
		for (int i = 0; i < list.length; i++) {
			lb.putInt(i * 4, list[i]);
		}
		for (int s = 0; s < this.grid.count; s++) {
			ab.putInt(s * 4, this.grid.active[s] ? 1 : 0);
		}
		this.listRing[ring].flush(0, Math.max(4L, list.length * 4L));
		this.activeRing[ring].flush(0, Math.max(4L, this.grid.count * 4L));

		int n = list.length;
		if (steps > 0 && n > 0) {
			float keep = (float) Math.pow(WaterSettings.hfDamping, dt * 60.0F) / (1.0F + WaterSettings.hfFriction * dt);
			int[] pc = this.push(n, dt, WaterSettings.gravity, this.cell, keep, WaterSettings.hfMaxSpeed, WaterSettings.hfRenderDepth,
				WaterSettings.hfMinFlowDepth, 0.0F, 0, 0, 0);
			int groups = n * this.groupsPerTile;
			for (int s = 0; s < steps; s++) {
				this.bind(p[0], cb, ubo, ring, this.stats);
				p[0].push(cb, pc);
				p[0].dispatch(cb, groups, 1, 1);
				Vk.barrier(cb);
				this.bind(p[1], cb, ubo, ring, this.stats);
				p[1].push(cb, pc);
				p[1].dispatch(cb, groups, 1, 1);
				Vk.barrier(cb);
			}
			this.stats.copyTo(cb, this.statsReadback[ring], 0, 0, (long) this.grid.count * this.groupsPerTile * STATS_BYTES);
			this.statsSlots[ring] = list.clone();
			Vk.barrier(cb);
			this.meshDirty = true;
			this.lastSteps = steps;
		} else {
			this.lastSteps = 0;
		}
		this.lastDt = dt;
	}

	/** Rebuilds the surface mesh only when the water moved, tiles were added or the render threshold changed. */
	void buildMesh(VkCommandBuffer cb, Desc ubo) {
		if (!this.meshDirty && this.meshRenderDepth == WaterSettings.hfRenderDepth) {
			return;
		}
		this.meshDirty = false;
		this.meshRenderDepth = WaterSettings.hfRenderDepth;
		ComputeProgram[] p = programs();
		int ring = Vk.ringSlot();
		this.meshCounters.fill(cb, 0);
		Vk.barrier(cb);
		this.bind(p[2], cb, ubo, ring, this.stats);
		p[2].push(cb, this.push(this.grid.count, 0.0F, 0.0F, this.cell, this.originY, 0.0F, WaterSettings.hfRenderDepth, 0.0F, 0.0F,
			this.quadCapacity, 0, 0));
		p[2].dispatch(cb, Math.max(1, this.grid.count * this.groupsPerTile), 1, 1);
		Vk.barrier(cb);
		p[4].bind(cb, ubo, Desc.ssbo(this.meshCounters));
		p[4].push(cb, 0, this.quadCapacity);
		p[4].dispatch(cb, 1, 1, 1);
		Vk.barrier(cb);
	}

	/** Sets the underwater flag in {@code frameView} when the camera (world coordinates) is inside heightfield water. */
	void probe(VkCommandBuffer cb, Desc ubo, VkBuf frameView, double camX, double camY, double camZ) {
		int bx = (int) Math.floor(camX);
		int bz = (int) Math.floor(camZ);
		int tx = this.grid.tileOfBlockX(bx);
		int tz = this.grid.tileOfBlockZ(bz);
		int slot = this.grid.slotAt(tx, tz);
		if (slot == HfTileGrid.NONE) {
			return;
		}
		int tn = this.grid.tn;
		int lx = Math.clamp((int) Math.floor((camX - this.originX - tx * (double) HfTileGrid.TILE_BLOCKS) * this.cellsPerBlock), 0, tn - 1);
		int lz = Math.clamp((int) Math.floor((camZ - this.originZ - tz * (double) HfTileGrid.TILE_BLOCKS) * this.cellsPerBlock), 0, tn - 1);
		ComputeProgram[] p = programs();
		this.bind(p[3], cb, ubo, Vk.ringSlot(), frameView);
		p[3].push(cb, this.push(0, (float) camY, 0.0F, 0.0F, 0.0F, 0.0F, WaterSettings.hfRenderDepth, 0.0F, 0.0F, slot, lx, lz));
		p[3].dispatch(cb, 1, 1, 1);
	}

	String statsLine() {
		HfTileGrid g = this.grid;
		double volume = g.totalVolume();
		double drift = this.initialVolume > 0.0 ? (volume - this.initialVolume) / this.initialVolume * 100.0 : 0.0;
		float maxDepth = 0.0F;
		for (int s = 0; s < g.count; s++) {
			maxDepth = Math.max(maxDepth, g.maxDepth[s]);
		}
		int awake = g.awakeCount(WaterSettings.hfSleepSeconds);
		String motion = g.activeList.length == 0 ? "asleep (settled)"
			: String.format("%d awake, %d simulated, %d steps x %.1f ms", awake, g.activeList.length, this.lastSteps, this.lastDt * 1000.0F);
		return String.format(
			"heightfield: %d/%d tiles (%d spans dropped), %.0f MB  |  volume %,.1f blocks (%+.3f%% since start)  |  max depth %.2f  max speed %.2f b/s  |  %s",
			g.count, g.maxTiles, g.droppedSpans, this.bytes() / 1.0e6, volume, drift, maxDepth, g.activeMax(g.maxSpeed), motion);
	}

	@Override
	public void close() {
		for (Pending p : this.pending) {
			Vk.destroyLater(p.staging());
		}
		this.pending.clear();
		List<AutoCloseable> all = new ArrayList<>(List.of(
			this.geo, this.depth, this.flux, this.links, this.tileInfo, this.stats, this.verts, this.quads, this.meshCounters));
		for (int i = 0; i < Vk.FRAME_RING; i++) {
			all.add(this.statsReadback[i]);
			all.add(this.listRing[i]);
			all.add(this.activeRing[i]);
		}
		for (AutoCloseable c : all) {
			Vk.destroyLater(c);
		}
	}
}
