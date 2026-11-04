package me.skaffy.client.realistic;

import me.skaffy.net.BreakRealisticPayload;
import me.skaffy.realistic.RealisticBoatEntity;
import me.skaffy.physics.RigidBody;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

public final class RealisticMining {
	private static int entityId = -1;
	private static int cx;
	private static int cy;
	private static int cz;
	private static float progress;
	private static int ticks;

	private RealisticMining() {
	}

	public static boolean start(Minecraft mc) {
		if (!(mc.hitResult instanceof RealisticHit hit) || mc.player == null || hit.getEntity() instanceof RealisticBoatEntity) {
			return false;
		}
		swing(mc);
		if (mc.player.hasInfiniteMaterials()) {
			send(hit);
			reset();
		} else {
			begin(hit);
		}
		return true;
	}

	public static boolean tick(Minecraft mc, boolean down) {
		if (!down || !(mc.hitResult instanceof RealisticHit hit) || mc.player == null || mc.level == null || hit.getEntity() instanceof RealisticBoatEntity) {
			reset();
			return false;
		}
		if (mc.player.hasInfiniteMaterials()) {
			return true;
		}
		if (hit.getEntity().getId() != entityId || hit.x != cx || hit.y != cy || hit.z != cz) {
			begin(hit);
		}
		RigidBody.Cell cell = hit.body().body.cellAt(hit.x, hit.y, hit.z);
		if (cell == null) {
			reset();
			return true;
		}
		BlockState state = (BlockState) cell.payload;
		BlockPos at = BlockPos.containing(hit.getLocation());
		progress += state.getDestroyProgress(mc.player, mc.level, at);
		if (ticks++ % 4 == 0) {
			SoundType sound = state.getSoundType();
			mc.level.playLocalSound(at, sound.getHitSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 8.0F, sound.getPitch() * 0.5F, false);
		}
		swing(mc);
		if (progress >= 1.0F) {
			send(hit);
			reset();
		}
		return true;
	}

	public static int stage(int id, int x, int y, int z) {
		if (id != entityId || x != cx || y != cy || z != cz || progress <= 0.0F) {
			return -1;
		}
		return Math.min((int) (progress * 10.0F), 9);
	}

	private static void begin(RealisticHit hit) {
		entityId = hit.getEntity().getId();
		cx = hit.x;
		cy = hit.y;
		cz = hit.z;
		progress = 0.0F;
		ticks = 0;
	}

	private static void reset() {
		entityId = -1;
		progress = 0.0F;
	}

	private static void send(RealisticHit hit) {
		if (ClientPlayNetworking.canSend(BreakRealisticPayload.TYPE)) {
			ClientPlayNetworking.send(new BreakRealisticPayload(hit.getEntity().getId(), hit.x, hit.y, hit.z));
		}
	}

	private static void swing(Minecraft mc) {
		mc.player.swing(InteractionHand.MAIN_HAND, mc.player.getMainHandItem().getAttackAnimation(), false);
	}
}
