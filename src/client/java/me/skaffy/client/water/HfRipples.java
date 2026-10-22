package me.skaffy.client.water;

import java.nio.ByteBuffer;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;

/**
 * The player's wake and splash rings on heightfield water, for composite.frag ({@code wakeAt}). While the player is at the surface
 * this records, 20 times a second, where it disturbed the water and by how much (moving pushes water aside, bobbing pushes it
 * down and draws it back, a splash drops a whole volume at once). The composite pass sums the deep-water ring wave that spreads
 * from each point since then, which forms a Kelvin wake behind a moving body and ring trains around splashes, evaluated per pixel
 * so it is sharp at any distance. It only shades the water; the solver's own waves move it (see hfStir and splashes).
 */
final class HfRipples implements AutoCloseable {
	static final int MAX = 160;
	private static final double WORLD_WRAP = 4096.0;
	private static final float STEP = 1.0F / 20.0F;
	/** Displaced volume per second and per block/s of horizontal (capped at 6) or vertical speed. */
	private static final float MOVE_VOLUME = 0.05F;
	private static final float BOB_VOLUME = 0.12F;
	/** Fraction of a splash's displaced volume (capped) that rings out as fine waves; the solver carries the bulk of it. */
	private static final float SPLASH_SHARE = 1.0F;
	private static final float SPLASH_MAX = 4.0F;

	private final VkBuf[] upload = new VkBuf[Vk.FRAME_RING];
	private final float[] ex = new float[MAX];
	private final float[] ez = new float[MAX];
	private final float[] et = new float[MAX];
	private final float[] ev = new float[MAX];
	private int head;
	private int size;
	private float time;
	private float pending;
	private int slot;

	/** Entries uploaded this frame, the water level they lie on (NaN before the first) and where the player is (wrapped world). */
	int count;
	float planeY = Float.NaN;
	float centerX;
	float centerZ;

	HfRipples() {
		for (int i = 0; i < Vk.FRAME_RING; i++) {
			this.upload[i] = VkBuf.upload(MAX * 16L, VkBuf.STORAGE);
		}
	}

	/** This frame's entries for composite.frag. */
	VkBuf current() {
		return this.upload[this.slot];
	}

	/** Same wrap as the shading globals, so world.xz in composite.frag and these points agree. */
	static double wrap(double v) {
		return v - Math.floor(v / WORLD_WRAP) * WORLD_WRAP;
	}

	private void add(double x, double z, float volume) {
		int i = (this.head + this.size) % MAX;
		if (this.size == MAX) {
			this.head = (this.head + 1) % MAX;
		} else {
			this.size++;
		}
		this.ex[i] = (float) wrap(x);
		this.ez[i] = (float) wrap(z);
		this.et[i] = this.time;
		this.ev[i] = volume;
	}

	/** A body hit the surface at (x, z), displacing {@code volume} blocks^3 (see HfWorld.splash): a ring train spreads from it. */
	void impact(double x, double z, double volume, float surface) {
		this.add(x, z, (float) -Math.min(volume * SPLASH_SHARE, SPLASH_MAX) * WaterSettings.hfRipples);
		this.planeY = surface;
	}

	/**
	 * Advances the clock, records where the player disturbs the surface and uploads the live points for this frame.
	 *
	 * @param sample the latest heightfield sample at the player ({u, w, surface, depth, found, floor}), or null
	 */
	void record(net.minecraft.client.Minecraft mc, float[] sample, float frameDt, boolean running) {
		var player = mc.player;
		if (running) {
			this.time += frameDt;
			this.pending = Math.min(this.pending + frameDt, 4.0F * STEP);
		}
		if (player != null) {
			this.centerX = (float) wrap(player.getX());
			this.centerZ = (float) wrap(player.getZ());
		}
		boolean contact = false;
		if (running && player != null && !player.isSpectator() && sample != null && sample[4] > 0.5F && sample[3] > 0.05F
			&& WaterSettings.hfRipples > 0.0F) {
			var box = player.getBoundingBox();
			float surface = sample[2];
			contact = box.minY < surface + 0.05 && box.maxY > surface - 0.3;
			if (contact) {
				this.planeY = surface;
			}
		}
		while (this.pending >= STEP) {
			this.pending -= STEP;
			if (!contact) {
				continue;
			}
			double vx = (player.getX() - player.xo) * 20.0;
			double vy = (player.getY() - player.yo) * 20.0;
			double vz = (player.getZ() - player.zo) * 20.0;
			float speed = (float) Math.min(Math.hypot(vx, vz), 6.0);
			float down = (float) Math.clamp(-vy, -6.0, 6.0);
			// Moving pushes water aside (a moving depression); going down pushes it away, coming up draws it back in.
			float volume = -STEP * (MOVE_VOLUME * speed + BOB_VOLUME * down) * WaterSettings.hfRipples;
			if (Math.abs(volume) > 1.0e-5F) {
				this.add(player.getX(), player.getZ(), volume);
			}
		}
		// Drop points whose waves have faded (after ~2 lifetimes they are below 15%).
		float maxAge = Math.max(1.0F, 2.0F * WaterSettings.hfWakeLife);
		while (this.size > 0 && this.time - this.et[this.head] > maxAge) {
			this.head = (this.head + 1) % MAX;
			this.size--;
		}
		this.slot = Vk.ringSlot();
		ByteBuffer b = this.upload[this.slot].mapped();
		for (int n = 0; n < this.size; n++) {
			int i = (this.head + n) % MAX;
			b.putFloat(n * 16, this.ex[i]).putFloat(n * 16 + 4, this.ez[i]).putFloat(n * 16 + 8, this.time - this.et[i]).putFloat(n * 16 + 12, this.ev[i]);
		}
		this.upload[this.slot].flush(0, Math.max(16L, this.size * 16L));
		this.count = this.size;
	}

	@Override
	public void close() {
		for (VkBuf b : this.upload) {
			Vk.destroyLater(b);
		}
	}
}
