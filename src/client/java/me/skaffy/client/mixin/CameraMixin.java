package me.skaffy.client.mixin;

import me.skaffy.client.water.WaterWorld;
import me.skaffy.fluid.RealisticLavaFluid;
import me.skaffy.fluid.RealisticWaterFluid;
import net.minecraft.client.Camera;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public class CameraMixin {
	@Shadow
	private boolean initialized;
	@Shadow
	private Level level;
	@Shadow
	private boolean detached;
	@Shadow
	private Vec3 position;

	@Shadow
	protected void setPosition(double x, double y, double z) {
	}

	/** First person: keep the eye off the heightfield water surface (WaterWorld.eyeY). */
	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void realisticWater$eyeOffSurface(float partialTicks, CallbackInfo ci) {
		if (!this.detached && this.position != null) {
			double y = WaterWorld.get().eyeY(this.position.y);
			if (y != this.position.y) {
				this.setPosition(this.position.x, y, this.position.z);
			}
		}
	}

	@Inject(method = "getFluidInCamera", at = @At("HEAD"), cancellable = true)
	private void realisticWater$noVanillaFog(CallbackInfoReturnable<FogType> cir) {
		if (this.initialized && this.level != null
			&& (RealisticWaterFluid.isRealistic(this.level.getFluidState(((Camera) (Object) this).blockPosition()))
				|| RealisticLavaFluid.isRealistic(this.level.getFluidState(((Camera) (Object) this).blockPosition())))) {
			cir.setReturnValue(FogType.NONE);
		}
	}
}
