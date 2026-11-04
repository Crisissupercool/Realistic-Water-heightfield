package me.skaffy.net;

import me.skaffy.RealisticWater;
import me.skaffy.realistic.RealisticBlockEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public record BodyStatePayload(int entityId, double x, double y, double z, float qx, float qy, float qz, float qw, float vx, float vy, float vz, float wx,
	float wy, float wz, byte flags) implements CustomPacketPayload {
	public static final byte TOUCHING = 1;
	public static final byte RELEASE = 2;
	public static final byte FLOATING = 4;
	public static final byte PADDLE_LEFT = 8;
	public static final byte PADDLE_RIGHT = 16;

	public static final Type<BodyStatePayload> TYPE = new Type<>(RealisticWater.id("body_state"));
	public static final StreamCodec<FriendlyByteBuf, BodyStatePayload> CODEC = CustomPacketPayload.codec(BodyStatePayload::write, BodyStatePayload::read);

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(this.entityId);
		buf.writeDouble(this.x);
		buf.writeDouble(this.y);
		buf.writeDouble(this.z);
		buf.writeFloat(this.qx);
		buf.writeFloat(this.qy);
		buf.writeFloat(this.qz);
		buf.writeFloat(this.qw);
		buf.writeFloat(this.vx);
		buf.writeFloat(this.vy);
		buf.writeFloat(this.vz);
		buf.writeFloat(this.wx);
		buf.writeFloat(this.wy);
		buf.writeFloat(this.wz);
		buf.writeByte(this.flags);
	}

	private static BodyStatePayload read(FriendlyByteBuf buf) {
		return new BodyStatePayload(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
			buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readByte());
	}

	public boolean has(byte flag) {
		return (this.flags & flag) != 0;
	}

	@Override
	public Type<BodyStatePayload> type() {
		return TYPE;
	}

	public void apply(ServerPlayer player) {
		if (player.level().getEntity(this.entityId) instanceof RealisticBlockEntity e) {
			e.acceptState(player, this);
		}
	}
}
