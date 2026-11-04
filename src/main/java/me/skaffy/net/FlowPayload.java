package me.skaffy.net;

import me.skaffy.RealisticWater;
import me.skaffy.realistic.RealisticBlockEntity;
import me.skaffy.realistic.RealisticPhysics;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public record FlowPayload(int entityId, float vx, float vy, float vz, float surfaceY) implements CustomPacketPayload {
	public static final Type<FlowPayload> TYPE = new Type<>(RealisticWater.id("body_flow"));
	public static final StreamCodec<RegistryFriendlyByteBuf, FlowPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, FlowPayload::entityId,
		ByteBufCodecs.FLOAT, FlowPayload::vx,
		ByteBufCodecs.FLOAT, FlowPayload::vy,
		ByteBufCodecs.FLOAT, FlowPayload::vz,
		ByteBufCodecs.FLOAT, FlowPayload::surfaceY,
		FlowPayload::new);

	@Override
	public Type<FlowPayload> type() {
		return TYPE;
	}

	public void apply(ServerPlayer player) {
		if (player.level().getEntity(this.entityId) instanceof RealisticBlockEntity e) {
			RealisticPhysics.reportFlow(e, this.vx, this.vy, this.vz, this.surfaceY);
		}
	}
}
