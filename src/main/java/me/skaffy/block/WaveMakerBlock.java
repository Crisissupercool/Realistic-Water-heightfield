package me.skaffy.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

public class WaveMakerBlock extends Block {
	public static final EnumProperty<Direction> FACING = DirectionalBlock.FACING;
	public static final IntegerProperty POWER = BlockStateProperties.POWER;
	public static final IntegerProperty SPEED = IntegerProperty.create("speed", 0, 2);
	private static final String[] SPEED_NAMES = {"slow", "medium", "fast"};

	public WaveMakerBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(POWER, 0).setValue(SPEED, 1));
	}

	@Override
	public @Nullable BlockState getStateForPlacement(final BlockPlaceContext context) {
		return this.defaultBlockState().setValue(FACING, placedFacing(context.getNearestLookingDirection()))
			.setValue(POWER, context.getLevel().getBestNeighborSignal(context.getClickedPos()));
	}

	public static Direction placedFacing(Direction look) {
		return look.getAxis().isVertical() ? look.getOpposite() : look;
	}

	@Override
	protected void neighborChanged(
		final BlockState state, final Level level, final BlockPos pos, final Block block, final @Nullable Orientation orientation, final boolean movedByPiston
	) {
		if (!level.isClientSide()) {
			int power = level.getBestNeighborSignal(pos);
			if (power != state.getValue(POWER)) {
				level.setBlock(pos, state.setValue(POWER, power), Block.UPDATE_CLIENTS);
			}
		}
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hitResult) {
		if (!level.isClientSide()) {
			BlockState next = state.cycle(SPEED);
			level.setBlock(pos, next, Block.UPDATE_CLIENTS);
			announceSpeed(level, player, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, next);
		}
		return InteractionResult.SUCCESS;
	}

	public static void announceSpeed(Level level, Player player, double x, double y, double z, BlockState next) {
		level.playSound(null, x, y, z, SoundEvents.COMPARATOR_CLICK, SoundSource.BLOCKS, 0.5F, 0.6F + 0.2F * next.getValue(SPEED));
		player.sendOverlayMessage(Component.translatable("block.realistic-water.wave_maker.speed",
			Component.translatable("block.realistic-water.wave_maker.speed." + SPEED_NAMES[next.getValue(SPEED)])));
	}

	@Override
	protected BlockState rotate(final BlockState state, final Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(final BlockState state, final Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, POWER, SPEED);
	}
}
