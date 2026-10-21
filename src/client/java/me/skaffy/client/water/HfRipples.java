package me.skaffy.client.water;

import me.skaffy.client.vk.ComputeProgram;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.Program;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Fine ripples around the player on heightfield water (hf_ripple.comp): a 256 x 256 wave field at 8 texels per block that the
 * composite pass uses to bend the water's normal. The solver's half-block cells can't show waves this small, so this is
 * what makes swimming, wading and jumping in visibly disturb the surface. It moves no water.
 */
final class HfRipples implements AutoCloseable {
	static final int SIZE = 256;
	static final int PER_BLOCK = 8;
	private static final double WORLD_WRAP = 4096.0;
	private static final float STEP = 1.0F / 60.0F;
	private static final float WAVE_SPEED = 1.5F;
	private static final float DAMPING = 0.995F;
	private static final float RELAX = 0.3F;

	private static ComputeProgram program;

	private final VkBuf[] field = new VkBuf[2];
	private int current;
	private boolean cleared;
	private float pending;
	private float time;

	/** Window min texel (wrapped world, see {@link #wrap}) and the water level the ripples lie on; NaN while there are none. */
	int winX;
	int winZ;
	float planeY = Float.NaN;

	HfRipples() {
		for (int i = 0; i < 2; i++) {
			this.field[i] = VkBuf.device((long) SIZE * SIZE * 8L, 0);
		}
		if (program == null) {
			program = new ComputeProgram("hf_ripple.comp", Desc.SSBO, Desc.SSBO);
		}
	}

	static void closeProgram() {
		if (program != null) {
			program.close();
			program = null;
		}
	}

	/** The field after the last step, for composite.frag. */
	VkBuf current() {
		return this.field[this.current];
	}

	/** Same wrap as the shading globals, so world.xz in composite.frag and these texels agree. */
	static double wrap(double v) {
		return v - Math.floor(v / WORLD_WRAP) * WORLD_WRAP;
	}

	/**
	 * Advances the ripples by this frame's time and stirs them where the player meets the heightfield water surface.
	 *
	 * @param sample the latest heightfield sample at the player ({u, w, surface, depth, found, floor}), or null
	 */
	void record(VkCommandBuffer cb, net.minecraft.client.Minecraft mc, float[] sample, float frameDt, boolean running) {
		var player = mc.player;
		if (!this.cleared) {
			this.field[0].fill(cb, 0);
			this.field[1].fill(cb, 0);
			Vk.barrier(cb);
			this.cleared = true;
		}
		if (player == null || !running || WaterSettings.hfRipples <= 0.0F) {
			return;
		}
		double px = wrap(player.getX()) * PER_BLOCK;
		double pz = wrap(player.getZ()) * PER_BLOCK;
		this.winX = (int) Math.floor(px) - SIZE / 2;
		this.winZ = (int) Math.floor(pz) - SIZE / 2;

		// Source: only while the body is at the water surface (wading, swimming, crossing it).
		float target = 0.0F;
		float radius = 0.0F;
		if (sample != null && sample[4] > 0.5F && sample[3] > 0.05F && !player.isSpectator()) {
			var box = player.getBoundingBox();
			float surface = sample[2];
			if (box.minY < surface + 0.05 && box.maxY > surface - 0.3) {
				double vx = (player.getX() - player.xo) * 20.0;
				double vy = (player.getY() - player.yo) * 20.0;
				double vz = (player.getZ() - player.zo) * 20.0;
				float speed = (float) Math.hypot(vx, vz);
				float still = 1.0F - Math.min(speed / 0.5F, 1.0F);
				// First in-game test: 6 cm per 3 b/s with a normal gain of 2 made reflections swim while walking; now a gentler wake.
				target = -0.025F * Math.min(speed, 3.0F) / 3.0F
					- 0.012F * still * (0.5F + 0.5F * (float) Math.sin(this.time * 5.0F))
					- 0.08F * (float) Math.min(Math.abs(vy) / 8.0, 1.0) * (Math.abs(vy) > 1.0 ? 1.0F : 0.0F);
				target *= WaterSettings.hfRipples;
				radius = (float) ((box.maxX - box.minX) * 0.5 + 0.1) * PER_BLOCK;
				this.planeY = surface;
			}
		}
		this.pending = Math.min(this.pending + frameDt, 4.0F * STEP);
		float c = WAVE_SPEED * PER_BLOCK * STEP;
		while (this.pending >= STEP) {
			this.pending -= STEP;
			this.time += STEP;
			VkBuf cur = this.field[this.current];
			VkBuf next = this.field[1 - this.current];
			program.bind(cb, Desc.ssbo(cur), Desc.ssbo(next));
			program.push(cb, this.winX, this.winZ, SIZE, 0,
				Program.f(c * c), Program.f(DAMPING), Program.f(RELAX), 0,
				Program.f((float) px), Program.f((float) pz), Program.f(radius), Program.f(target));
			program.dispatch(cb, SIZE / 16, SIZE / 16, 1);
			Vk.barrier(cb);
			this.current = 1 - this.current;
		}
	}

	@Override
	public void close() {
		for (VkBuf b : this.field) {
			Vk.destroyLater(b);
		}
	}
}
