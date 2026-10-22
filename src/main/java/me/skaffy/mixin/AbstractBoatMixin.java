package me.skaffy.mixin;

import me.skaffy.ModContent;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A boat whose top dips under the surface asks whether that water is a source: if not, vanilla treats it as flowing water
 * pouring over the boat (status UNDER_FLOWING_WATER) and pushes it down every tick, which is how waterfalls sink boats.
 * Realistic water is never a source block (a source is an infinite spring), so every boat that dipped under once, landing in
 * the water or riding a wave, sank. Realistic water counts as still water here, so the boat gets vanilla's buoyancy instead.
 */
@Mixin(AbstractBoat.class)
public class AbstractBoatMixin {
	@Redirect(method = "isUnderwater", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FluidState;isSource()Z"))
	private boolean realisticWater$realisticWaterIsStill(FluidState fluid) {
		return fluid.isSource() || fluid.getType().isSame(ModContent.REALISTIC_WATER);
	}
}
