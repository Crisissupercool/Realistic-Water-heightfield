package me.skaffy.realistic;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

public class ConverterItem extends Item {
	public ConverterItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(final Level level, final Player player, final InteractionHand hand) {
		if (!level.isClientSide()) {
			player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new ConverterMenu(id, inventory, ContainerLevelAccess.create(level, p.blockPosition())),
				Component.translatable("container.realistic-water.converter")));
		}
		return InteractionResult.SUCCESS;
	}
}
