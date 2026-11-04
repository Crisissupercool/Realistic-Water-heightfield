package me.skaffy.net;

import me.skaffy.RealisticWater;
import me.skaffy.fluid.FluidMixing;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public record FluidMixPayload(long[] positions) implements CustomPacketPayload {
	public static final Type<FluidMixPayload> TYPE = new Type<>(RealisticWater.id("fluid_mix"));
	public static final StreamCodec<RegistryFriendlyByteBuf, FluidMixPayload> CODEC = StreamCodec.of(FluidMixPayload::write, FluidMixPayload::read);
	public static final int MAX_ENTRIES = 4096;

	private static void write(RegistryFriendlyByteBuf buf, FluidMixPayload payload) {
		buf.writeVarInt(payload.positions.length);
		for (long p : payload.positions) {
			buf.writeLong(p);
		}
	}

	private static FluidMixPayload read(RegistryFriendlyByteBuf buf) {
		int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
		long[] positions = new long[n];
		for (int i = 0; i < n; i++) {
			positions[i] = buf.readLong();
		}
		return new FluidMixPayload(positions);
	}

	@Override
	public Type<FluidMixPayload> type() {
		return TYPE;
	}

	public void apply(ServerPlayer player) {
		ServerLevel level = player.level();
		for (long key : this.positions) {
			BlockPos pos = BlockPos.of(key);
			if (level.isLoaded(pos) && pos.distSqr(player.blockPosition()) <= 512 * 512 && !FluidMixing.solidifyContact(level, pos)) {
				player.connection.send(new ClientboundBlockUpdatePacket(level, pos));
			}
		}
	}
}
