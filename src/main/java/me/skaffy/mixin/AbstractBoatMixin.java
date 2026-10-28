package me.skaffy.mixin;

import me.skaffy.fluid.RealisticWaterFluid;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Boats on realistic (heightfield) water float on the water column's surface (fork addition, docs/HEIGHTFIELD_DESIGN.md
 * section 25).
 * <p>
 * Vanilla only knows still lakes: it floats a boat on the surface of the block its bottom is in, and once the water is above
 * the boat's top it calls the boat underwater and pulls it down (UNDER_WATER sinks at about 2.4 blocks/s, UNDER_FLOWING_WATER
 * slowly; neither ever pushes it back up). Heightfield water rises (floods), sloshes and crosses block boundaries, and the
 * written blocks step 2/9 of a block at a boundary (8/9 high top block, then 1 + 1/9), more than a floating boat's 0.2 block
 * freeboard: any boat caught by that sank to the bottom. Here realistic water never counts as over the boat, and the water
 * level the boat floats on is the column's surface (block amounts / 8, up to 3 blocks up), so a submerged boat rises back up
 * with vanilla's own buoyancy (about 5 blocks/s at most) and rides rising water. The surface is lowered by 1/9 like the drawn
 * surface (hfSurfaceDrop), so a boat sits in the water as it does on vanilla water.
 */
@Mixin(AbstractBoat.class)
public class AbstractBoatMixin {
	@Redirect(method = "isUnderwater", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FluidState;is(Lnet/minecraft/tags/TagKey;)Z"))
	private boolean realisticWater$neverUnderRealisticWater(FluidState fluid, TagKey<Fluid> tag) {
		return fluid.is(tag) && !RealisticWaterFluid.isRealistic(fluid);
	}

	@Redirect(method = "checkInWater", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/material/FluidState;getHeight(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F"))
	private float realisticWater$columnSurface(FluidState fluid, BlockGetter level, BlockPos pos) {
		if (!RealisticWaterFluid.isRealistic(fluid)) {
			return fluid.getHeight(level, pos);
		}
		return realisticWater$column(level, pos.immutable(), fluid);
	}

	/** Height of the realistic water column above the bottom of pos, up to its surface (at most 3 blocks), as drawn. */
	@Unique
	private static float realisticWater$column(BlockGetter level, BlockPos pos, FluidState fluid) {
		float h = 0.0F;
		FluidState top = fluid;
		int k = 0;
		while (k < 2) {
			FluidState above = level.getFluidState(pos.above(k + 1));
			if (!RealisticWaterFluid.isRealistic(above)) {
				break;
			}
			h += 1.0F;
			top = above;
			k++;
		}
		h += top.getAmount() / 8.0F;
		boolean deep = RealisticWaterFluid.isRealistic(level.getFluidState(pos.below()));
		return h - (deep ? 1.0F : Math.min(h, 1.0F)) / 9.0F;
	}
}
