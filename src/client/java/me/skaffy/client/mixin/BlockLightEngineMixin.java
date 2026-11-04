package me.skaffy.client.mixin;

import me.skaffy.client.realistic.DynamicLights;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.BlockLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockLightEngine.class)
public abstract class BlockLightEngineMixin {
	@Inject(method = "getEmission", at = @At("RETURN"), cancellable = true)
	private void realisticWater$realisticTorches(long blockNode, BlockState state, CallbackInfoReturnable<Integer> cir) {
		if (!DynamicLights.any()) {
			return;
		}
		int extra = DynamicLights.emission(blockNode);
		if (extra > cir.getReturnValueI() && ((LightEngineAccessor) this).realisticWater$chunkSource().getLevel() instanceof ClientLevel) {
			cir.setReturnValue(extra);
		}
	}
}
