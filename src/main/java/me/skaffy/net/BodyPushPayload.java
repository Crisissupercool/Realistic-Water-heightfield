package me.skaffy.net;

import me.skaffy.RealisticWater;
import me.skaffy.realistic.RealisticBlockEntity;
import me.skaffy.realistic.RealisticPhysics;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public record BodyPushPayload(int entityId, float px, float py, float pz, float dx, float dy, float dz, float target, float force, float kick)
	implements CustomPacketPayload {
	public static final Type<BodyPushPayload> TYPE = new Type<>(RealisticWater.id("body_push"));
	public static final StreamCodec<FriendlyByteBuf, BodyPushPayload> CODEC = CustomPacketPayload.codec(BodyPushPayload::write, BodyPushPayload::read);

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(this.entityId);
		buf.writeFloat(this.px);
		buf.writeFloat(this.py);
		buf.writeFloat(this.pz);
		buf.writeFloat(this.dx);
		buf.writeFloat(this.dy);
		buf.writeFloat(this.dz);
		buf.writeFloat(this.target);
		buf.writeFloat(this.force);
		buf.writeFloat(this.kick);
	}

	private static BodyPushPayload read(FriendlyByteBuf buf) {
		return new BodyPushPayload(buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
			buf.readFloat(), buf.readFloat(), buf.readFloat());
	}

	@Override
	public Type<BodyPushPayload> type() {
		return TYPE;
	}

	public void apply(ServerPlayer player) {
		if (!(player.level().getEntity(this.entityId) instanceof RealisticBlockEntity e)) {
			return;
		}
		if (player.distanceToSqr(this.px, this.py, this.pz) > 16.0 || !Float.isFinite(this.force) || this.force > 1.0E7F || this.kick > 1.0E6F) {
			return;
		}
		RealisticPhysics.push(e, this.px, this.py, this.pz, this.dx, this.dy, this.dz, this.target, this.force, this.kick);
	}
}
