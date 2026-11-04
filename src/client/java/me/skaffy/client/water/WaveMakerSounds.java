package me.skaffy.client.water;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.skaffy.ModContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

final class WaveMakerSounds {
	private static final double HEAR_RANGE = 24.0;

	private static final class Loop extends AbstractTickableSoundInstance {
		boolean seen;

		Loop(Vec3 at) {
			super(ModContent.WAVE_MAKER_RUN, SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
			this.looping = true;
			this.delay = 0;
			this.volume = 1.0F;
			this.moveTo(at);
		}

		void moveTo(Vec3 at) {
			this.x = at.x;
			this.y = at.y;
			this.z = at.z;
		}

		void end() {
			this.stop();
		}

		@Override
		public void tick() {
		}
	}

	private final Map<Object, Loop> playing = new HashMap<>();

	void tick(Minecraft mc, List<WaterWorld.Pump> pumps) {
		Vec3 listener = mc.player != null ? mc.player.getEyePosition() : null;
		for (Loop loop : this.playing.values()) {
			loop.seen = false;
		}
		if (listener != null) {
			for (WaterWorld.Pump pump : pumps) {
				if (pump.center().distanceToSqr(listener) > HEAR_RANGE * HEAR_RANGE) {
					continue;
				}
				Loop loop = this.playing.get(pump.key());
				if (loop == null || loop.isStopped()) {
					loop = new Loop(pump.center());
					this.playing.put(pump.key(), loop);
					mc.getSoundManager().play(loop);
				}
				loop.moveTo(pump.center());
				loop.seen = true;
			}
		}
		this.playing.values().removeIf(loop -> {
			if (!loop.seen) {
				loop.end();
				return true;
			}
			return false;
		});
	}

	void clear() {
		for (Loop loop : this.playing.values()) {
			loop.end();
		}
		this.playing.clear();
	}
}
