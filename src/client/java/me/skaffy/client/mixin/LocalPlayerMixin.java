package me.skaffy.client.mixin;

import me.skaffy.client.realistic.ClientBodies;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public class LocalPlayerMixin {
	@Inject(method = "move", at = @At("HEAD"))
	private void realisticWater$pushBodies(MoverType moverType, Vec3 delta, CallbackInfo ci) {
		if (moverType == MoverType.SELF) {
			ClientBodies.beforePlayerMove((LocalPlayer) (Object) this, delta);
		}
	}
}
