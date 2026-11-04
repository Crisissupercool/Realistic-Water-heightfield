package me.skaffy.realistic;

import me.skaffy.ModContent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class ConverterMenu extends AbstractContainerMenu {
	private static final int INPUT = 0;
	private static final int RESULT = 1;
	private static final int INV_START = 2;
	private static final int INV_END = 38;

	private final ContainerLevelAccess access;
	private final Container input = new SimpleContainer(1) {
		@Override
		public void setChanged() {
			super.setChanged();
			ConverterMenu.this.slotsChanged(this);
		}
	};
	private final ResultContainer result = new ResultContainer();

	public ConverterMenu(final int containerId, final Inventory inventory) {
		this(containerId, inventory, ContainerLevelAccess.NULL);
	}

	public ConverterMenu(final int containerId, final Inventory inventory, final ContainerLevelAccess access) {
		super(ModContent.CONVERTER_MENU, containerId);
		this.access = access;
		this.addSlot(new Slot(this.input, 0, 56, 35) {
			@Override
			public boolean mayPlace(final ItemStack stack) {
				return RealisticBlockItem.convertible(stack);
			}
		});
		this.addSlot(new Slot(this.result, 0, 116, 35) {
			@Override
			public boolean mayPlace(final ItemStack stack) {
				return false;
			}

			@Override
			public void onTake(final Player player, final ItemStack carried) {
				ConverterMenu.this.input.removeItem(0, carried.getCount());
				access.execute((level, pos) -> level.playSound(null, pos, SoundEvents.UI_STONECUTTER_TAKE_RESULT, SoundSource.BLOCKS, 1.0F, 1.0F));
				super.onTake(player, carried);
			}
		});
		this.addStandardInventorySlots(inventory, 8, 84);
	}

	@Override
	public void slotsChanged(final Container container) {
		super.slotsChanged(container);
		if (container == this.input) {
			this.result.setItem(0, RealisticBlockItem.convert(this.input.getItem(0)));
			this.broadcastChanges();
		}
	}

	@Override
	public ItemStack quickMoveStack(final Player player, final int slotIndex) {
		Slot slot = this.slots.get(slotIndex);
		if (!slot.hasItem()) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = slot.getItem();
		ItemStack copy = stack.copy();
		if (slotIndex == RESULT) {
			if (!this.moveItemStackTo(stack, INV_START, INV_END, true)) {
				return ItemStack.EMPTY;
			}
			int moved = copy.getCount() - stack.getCount();
			slot.onTake(player, copy.copyWithCount(moved));
			this.slotsChanged(this.input);
			return ItemStack.EMPTY;
		}
		if (slotIndex == INPUT) {
			if (!this.moveItemStackTo(stack, INV_START, INV_END, false)) {
				return ItemStack.EMPTY;
			}
		} else if (RealisticBlockItem.convertible(stack)) {
			if (!this.moveItemStackTo(stack, INPUT, INPUT + 1, false)) {
				return ItemStack.EMPTY;
			}
		} else if (slotIndex < INV_END - 9) {
			if (!this.moveItemStackTo(stack, INV_END - 9, INV_END, false)) {
				return ItemStack.EMPTY;
			}
		} else if (!this.moveItemStackTo(stack, INV_START, INV_END - 9, false)) {
			return ItemStack.EMPTY;
		}
		if (stack.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}
		return copy.getCount() == stack.getCount() ? ItemStack.EMPTY : copy;
	}

	@Override
	public boolean stillValid(final Player player) {
		return true;
	}

	@Override
	public void removed(final Player player) {
		super.removed(player);
		this.result.removeItemNoUpdate(0);
		this.access.execute((level, pos) -> this.clearContainer(player, this.input));
	}
}
