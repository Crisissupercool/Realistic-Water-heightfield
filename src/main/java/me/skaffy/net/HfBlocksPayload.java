package me.skaffy.net;

import me.skaffy.ModContent;
import me.skaffy.RealisticWater;
import me.skaffy.block.RealisticWaterBlock;
import me.skaffy.fluid.RealisticWaterFluid;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block writes for the heightfield water (fork addition, see docs/HEIGHTFIELD_DESIGN.md section 14). Three modes:
 * <ul>
 *   <li>{@link #WRITE}: the heightfield's water levels into realistic water blocks (only touches realistic water and air), like
 *       {@link WaterSyncPayload};</li>
 *   <li>{@link #CAPTURE}: vanilla water blocks into realistic water of the given amount, so the heightfield can take them over;</li>
 *   <li>{@link #RELEASE}: realistic water (or air) back into vanilla water of the given amount; 0 clears realistic water.</li>
 * </ul>
 * Springs (infinite realistic water) are never touched.
 */
public record HfBlocksPayload(byte mode, long[] positions, byte[] amounts) implements CustomPacketPayload {
	public static final byte WRITE = 0;
	public static final byte CAPTURE = 1;
	public static final byte RELEASE = 2;
	public static final Type<HfBlocksPayload> TYPE = new Type<>(RealisticWater.id("hf_blocks"));
	public static final StreamCodec<RegistryFriendlyByteBuf, HfBlocksPayload> CODEC = StreamCodec.of(HfBlocksPayload::write, HfBlocksPayload::read);
	public static final int MAX_ENTRIES = 32768;
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;

	private static void write(RegistryFriendlyByteBuf buf, HfBlocksPayload payload) {
		buf.writeByte(payload.mode);
		buf.writeVarInt(payload.positions.length);
		for (int i = 0; i < payload.positions.length; i++) {
			buf.writeLong(payload.positions[i]);
			buf.writeByte(payload.amounts[i]);
		}
	}

	private static HfBlocksPayload read(RegistryFriendlyByteBuf buf) {
		byte mode = buf.readByte();
		int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
		long[] positions = new long[n];
		byte[] amounts = new byte[n];
		for (int i = 0; i < n; i++) {
			positions[i] = buf.readLong();
			amounts[i] = buf.readByte();
		}
		return new HfBlocksPayload(mode, positions, amounts);
	}

	@Override
	public Type<HfBlocksPayload> type() {
		return TYPE;
	}

	private static BlockState vanilla(int amount) {
		BlockState source = Blocks.WATER.defaultBlockState();
		return amount >= 8 ? source : source.setValue(LiquidBlock.LEVEL, 8 - Math.max(1, amount));
	}

	public void apply(ServerPlayer player) {
		ServerLevel level = player.level();
		for (int i = 0; i < this.positions.length; i++) {
			BlockPos pos = BlockPos.of(this.positions[i]);
			if (!level.isLoaded(pos) || pos.distSqr(player.blockPosition()) > 512 * 512) {
				continue;
			}
			BlockState current = level.getBlockState(pos);
			if (RealisticWaterBlock.isInfinite(current)) {
				continue;
			}
			boolean ours = RealisticWaterFluid.isRealistic(current.getFluidState()) && current.getBlock() == ModContent.REALISTIC_WATER_BLOCK;
			int amount = this.amounts[i];
			BlockState target;
			switch (this.mode) {
				case CAPTURE -> {
					if (current.getBlock() != Blocks.WATER || amount <= 0) {
						continue;
					}
					target = WaterSyncPayload.stateFor(amount);
				}
				case RELEASE -> {
					if (!ours && !current.isAir()) {
						continue;
					}
					target = amount <= 0 ? Blocks.AIR.defaultBlockState() : vanilla(amount);
				}
				default -> {
					// Replaceable plants (grass, ferns, vines, a thin snow layer) give way to the water like they do to vanilla water.
					// The heightfield already fills them (they have no collision); leaving them standing kept whole grassland floods
					// out of the blocks, so mobs walked and burned in water that was only drawn.
					boolean plant = !ours && amount > 0 && !current.isAir() && current.canBeReplaced() && current.getFluidState().isEmpty();
					if (!ours && !current.isAir() && !plant) {
						continue;
					}
					target = amount <= 0 ? Blocks.AIR.defaultBlockState() : WaterSyncPayload.stateFor(amount);
					if (plant) {
						Block.dropResources(current, level, pos);
						// A tall plant's top half (the same block above) goes with it; these writes skip neighbor updates.
						BlockPos up = pos.above();
						if (level.getBlockState(up).is(current.getBlock())) {
							level.setBlock(up, Blocks.AIR.defaultBlockState(), FLAGS);
						}
					}
				}
			}
			if (current != target) {
				level.setBlock(pos, target, FLAGS);
			}
		}
	}
}
