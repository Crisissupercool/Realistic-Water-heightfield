package me.skaffy.client.mixin.compat;

import me.skaffy.client.compat.ShaderCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.vitrail.render.PackChain", remap = false)
public abstract class VitrailPackChainMixin {
	@Inject(method = "closeFeatures", at = @At("HEAD"), require = 0, remap = false)
	private static void realisticWater$drawInsidePack(CallbackInfo ci) {
		ShaderCompat.vitrailFeaturesClosing();
	}
}
