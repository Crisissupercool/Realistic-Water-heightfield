package me.skaffy.client.water;

import java.nio.ByteBuffer;
import me.skaffy.client.vk.ComputeProgram;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.Program;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * One dense rectangle of heightfield water (milestone 1). Owns the GPU buffers for spans, links, fluxes and the render mesh,
 * and records the flux, depth, mesh and probe dispatches. Sparse tiles replace the dense rectangle in milestone 3.
 */
final class HfDomain implements AutoCloseable {
	private static final int STATS_BYTES = 16;

	private static ComputeProgram[] programs;

	private static ComputeProgram[] programs() {
		if (programs == null) {
			int U = Desc.UBO;
			int S = Desc.SSBO;
			programs = new ComputeProgram[]{
				new ComputeProgram("hf_flux.comp", U, S, S, S, S),
				new ComputeProgram("hf_depth.comp", U, S, S, S, S, S),
				new ComputeProgram("hf_mesh.comp", U, S, S, S, S, S, S),
				new ComputeProgram("hf_probe.comp", U, S, S, S),
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

	final int originX;
	final int originZ;
	final int originY;
	final int sizeX;
	final int sizeZ;
	final int cellsPerBlock;
	final float cell;
	final int nx;
	final int nz;
	final int layers;
	final int spans;
	final int droppedSpans;
	final int quadCapacity;
	final double initialVolume;

	private final VkBuf geo;
	private final VkBuf depth;
	private final VkBuf flux;
	private final VkBuf links;
	private final VkBuf stats;
	private final VkBuf[] statsReadback = new VkBuf[Vk.FRAME_RING];
	private final boolean[] statsPending = new boolean[Vk.FRAME_RING];
	final VkBuf verts;
	final VkBuf quads;
	final VkBuf meshCounters;
	private VkBuf geoUpload;
	private VkBuf depthUpload;
	private VkBuf linksUpload;
	private final int groups;
	private final int statsSlots;

	double lastVolume;
	float lastMaxDepth;
	float lastMaxSpeed;
	int lastSteps;
	float lastDt;

	HfDomain(HfTopology topo, float[] initialDepth, int originX, int originY, int originZ, int sizeX, int sizeZ, int cellsPerBlock) {
		this.originX = originX;
		this.originY = originY;
		this.originZ = originZ;
		this.sizeX = sizeX;
		this.sizeZ = sizeZ;
		this.cellsPerBlock = cellsPerBlock;
		this.cell = 1.0F / cellsPerBlock;
		this.nx = topo.nx;
		this.nz = topo.nz;
		this.layers = topo.layers;
		this.spans = topo.spans;
		this.droppedSpans = topo.droppedSpans;
		this.groups = ComputeProgram.groups(this.spans, 256);
		this.statsSlots = ComputeProgram.launched(this.groups);
		this.quadCapacity = Math.clamp((long) this.nx * this.nz * 3L, 65_536, 2_000_000);

		this.geoUpload = VkBuf.upload(this.spans * 8L, 0);
		this.depthUpload = VkBuf.upload(this.spans * 4L, 0);
		this.linksUpload = VkBuf.upload(this.spans * (long) HfTopology.LINKS * 4L, 0);
		ByteBuffer g = this.geoUpload.mapped();
		ByteBuffer d = this.depthUpload.mapped();
		double volume = 0.0;
		float maxDepth = 0.0F;
		float area = this.cell * this.cell;
		for (int s = 0; s < this.spans; s++) {
			g.putFloat(s * 8, topo.floor[s]).putFloat(s * 8 + 4, topo.ceil[s]);
			float h = topo.valid(s) ? initialDepth[s] : 0.0F;
			d.putFloat(s * 4, h);
			volume += (double) h * area;
			maxDepth = Math.max(maxDepth, h);
		}
		this.linksUpload.mapped().asIntBuffer().put(0, topo.links);
		this.geoUpload.flush(0, this.spans * 8L);
		this.depthUpload.flush(0, this.spans * 4L);
		this.linksUpload.flush(0, this.spans * (long) HfTopology.LINKS * 4L);
		this.initialVolume = volume;
		this.lastVolume = volume;
		this.lastMaxDepth = maxDepth;

		this.geo = VkBuf.device(this.spans * 8L, 0);
		this.depth = VkBuf.device(this.spans * 4L, 0);
		this.flux = VkBuf.device(this.spans * (long) HfTopology.LINKS * 4L, 0);
		this.links = VkBuf.device(this.spans * (long) HfTopology.LINKS * 4L, 0);
		this.stats = VkBuf.device((long) this.statsSlots * STATS_BYTES, 0);
		for (int i = 0; i < Vk.FRAME_RING; i++) {
			this.statsReadback[i] = VkBuf.readback((long) this.statsSlots * STATS_BYTES);
		}
		this.verts = VkBuf.device(this.quadCapacity * 4L * 16L, 0);
		this.quads = VkBuf.device(this.quadCapacity * 16L, 0);
		this.meshCounters = VkBuf.device(64, VkBuf.INDIRECT);
		programs();
	}

	/** Size of the GPU allocation, for /water hf stats. */
	long bytes() {
		return this.spans * (8L + 4L + HfTopology.LINKS * 8L) + this.quadCapacity * 80L;
	}

	/** Reads the stats this ring slot recorded {@link Vk#FRAME_RING} frames ago. Call once per frame before {@link #record}. */
	void readStats() {
		int slot = Vk.ringSlot();
		if (!this.statsPending[slot]) {
			return;
		}
		this.statsPending[slot] = false;
		VkBuf rb = this.statsReadback[slot];
		rb.invalidate(0, (long) this.statsSlots * STATS_BYTES);
		ByteBuffer b = rb.mapped();
		double volume = 0.0;
		float maxDepth = 0.0F;
		float maxSpeed = 0.0F;
		for (int i = 0; i < this.statsSlots; i++) {
			int o = i * STATS_BYTES;
			volume += b.getFloat(o);
			maxDepth = Math.max(maxDepth, b.getFloat(o + 4));
			maxSpeed = Math.max(maxSpeed, b.getFloat(o + 8));
		}
		this.lastVolume = volume;
		this.lastMaxDepth = maxDepth;
		this.lastMaxSpeed = maxSpeed;
	}

	/** Largest stable time step for the last known depth and speed (shallow-water CFL condition). */
	float maxStableDt() {
		float waveSpeed = (float) Math.sqrt(WaterSettings.gravity * Math.max(this.lastMaxDepth, 0.01F)) + this.lastMaxSpeed;
		return WaterSettings.hfCfl * this.cell / Math.max(waveSpeed, 1.0e-3F);
	}

	private int[] push(float a, float b, float c, float d, float e, float f, float g, float h, int i) {
		return new int[]{
			this.nx, this.nz, this.layers, this.spans,
			Program.f(a), Program.f(b), Program.f(c), Program.f(d),
			Program.f(e), Program.f(f), Program.f(g), Program.f(h),
			i, 0, 0, 0
		};
	}

	void record(VkCommandBuffer cb, Desc ubo, int steps, float dt) {
		ComputeProgram[] p = programs();
		if (this.geoUpload != null) {
			this.geoUpload.copyTo(cb, this.geo, 0, 0, this.spans * 8L);
			this.depthUpload.copyTo(cb, this.depth, 0, 0, this.spans * 4L);
			this.linksUpload.copyTo(cb, this.links, 0, 0, this.spans * (long) HfTopology.LINKS * 4L);
			this.flux.fill(cb, 0);
			Vk.destroyLater(this.geoUpload);
			Vk.destroyLater(this.depthUpload);
			Vk.destroyLater(this.linksUpload);
			this.geoUpload = null;
			this.depthUpload = null;
			this.linksUpload = null;
			Vk.barrier(cb);
		}
		float keep = (float) Math.pow(WaterSettings.hfDamping, dt * 60.0F) / (1.0F + WaterSettings.hfFriction * dt);
		int[] pc = this.push(dt, WaterSettings.gravity, this.cell, keep, WaterSettings.hfMaxSpeed, WaterSettings.hfRenderDepth, 0.0F, 0.0F, 0);
		for (int s = 0; s < steps; s++) {
			p[0].bind(cb, ubo, Desc.ssbo(this.geo), Desc.ssbo(this.depth), Desc.ssbo(this.flux), Desc.ssbo(this.links));
			p[0].push(cb, pc);
			p[0].dispatch(cb, this.groups, 1, 1);
			Vk.barrier(cb);
			p[1].bind(cb, ubo, Desc.ssbo(this.geo), Desc.ssbo(this.depth), Desc.ssbo(this.flux), Desc.ssbo(this.links), Desc.ssbo(this.stats));
			p[1].push(cb, pc);
			p[1].dispatch(cb, this.groups, 1, 1);
			Vk.barrier(cb);
		}
		if (steps > 0) {
			int slot = Vk.ringSlot();
			this.stats.copyTo(cb, this.statsReadback[slot], 0, 0, (long) this.statsSlots * STATS_BYTES);
			this.statsPending[slot] = true;
			Vk.barrier(cb);
		}
		this.lastSteps = steps;
		this.lastDt = dt;
	}

	void buildMesh(VkCommandBuffer cb, Desc ubo) {
		ComputeProgram[] p = programs();
		this.meshCounters.fill(cb, 0);
		Vk.barrier(cb);
		p[2].bind(cb, ubo, Desc.ssbo(this.geo), Desc.ssbo(this.depth), Desc.ssbo(this.links), Desc.ssbo(this.verts), Desc.ssbo(this.quads),
			Desc.ssbo(this.meshCounters));
		p[2].push(cb, this.push(0.0F, 0.0F, this.cell, this.originY, 0.0F, WaterSettings.hfRenderDepth, 0.0F, 0.0F, this.quadCapacity));
		p[2].dispatch(cb, this.groups, 1, 1);
		Vk.barrier(cb);
		p[4].bind(cb, ubo, Desc.ssbo(this.meshCounters));
		p[4].push(cb, 0, this.quadCapacity);
		p[4].dispatch(cb, 1, 1, 1);
		Vk.barrier(cb);
	}

	/** @param camX camera x relative to {@link #originX}; camY in world blocks; camZ relative to {@link #originZ} */
	void probe(VkCommandBuffer cb, Desc ubo, VkBuf frameView, float camX, float camY, float camZ) {
		ComputeProgram[] p = programs();
		p[3].bind(cb, ubo, Desc.ssbo(this.geo), Desc.ssbo(this.depth), Desc.ssbo(frameView));
		p[3].push(cb, this.push(camX, camY, camZ, this.cell, 0.0F, WaterSettings.hfRenderDepth, 0.0F, 0.0F, 0));
		p[3].dispatch(cb, 1, 1, 1);
	}

	String statsLine() {
		double drift = this.initialVolume > 0.0 ? (this.lastVolume - this.initialVolume) / this.initialVolume * 100.0 : 0.0;
		return String.format(
			"heightfield: %dx%d blocks at (%d, %d), %,d spans (%d dropped), %.0f MB  |  volume %,.1f blocks (%+.3f%% since start)  |  "
				+ "max depth %.2f  max speed %.1f b/s  |  %d steps x %.1f ms",
			this.sizeX, this.sizeZ, this.originX, this.originZ, this.spans, this.droppedSpans, this.bytes() / 1.0e6, this.lastVolume, drift,
			this.lastMaxDepth, this.lastMaxSpeed, this.lastSteps, this.lastDt * 1000.0F);
	}

	@Override
	public void close() {
		for (AutoCloseable c : new AutoCloseable[]{
			this.geo, this.depth, this.flux, this.links, this.stats, this.verts, this.quads, this.meshCounters,
			this.geoUpload, this.depthUpload, this.linksUpload
		}) {
			if (c != null) {
				Vk.destroyLater(c);
			}
		}
		for (VkBuf rb : this.statsReadback) {
			Vk.destroyLater(rb);
		}
	}
}
