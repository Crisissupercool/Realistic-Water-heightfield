package me.skaffy.realistic;

import me.skaffy.ModContent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

public class RealisticBoatItem extends Item {
	public RealisticBoatItem(Properties properties) {
		super(properties);
	}

	public static ItemStack of(Item boat, int count) {
		ItemStack stack = new ItemStack(ModContent.REALISTIC_BOAT_ITEM, count);
		Identifier id = BuiltInRegistries.ITEM.getKey(boat);
		stack.set(ModContent.SOURCE_ITEM, id);
		stack.set(DataComponents.ITEM_MODEL, id);
		stack.set(DataComponents.ITEM_NAME, Component.translatable("item.realistic-water.realistic_block.of", boat.getName(new ItemStack(boat))));
		return stack;
	}

	public static @Nullable BoatItem boatOf(ItemStack stack) {
		Identifier id = stack.get(ModContent.SOURCE_ITEM);
		return id != null && BuiltInRegistries.ITEM.getOptional(id).orElse(null) instanceof BoatItem boat ? boat : null;
	}

	@Override
	public InteractionResult use(final Level level, final Player player, final InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		BoatItem boat = boatOf(stack);
		HitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.ANY);
		if (boat == null || !(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
			return InteractionResult.PASS;
		}
		if (level instanceof ServerLevel server) {
			RealisticBoatEntity.spawn(server, boat, block.getLocation(), player.getYRot());
			level.gameEvent(player, GameEvent.ENTITY_PLACE, block.getLocation());
			stack.consume(1, player);
		}
		return InteractionResult.SUCCESS;
	}
}
