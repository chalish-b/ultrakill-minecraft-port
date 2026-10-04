package dev.ultracraft.net;

import dev.ultracraft.Ultracraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/** Client to server: the local player's slam came down on an enemy (GroundCheck), where it hit. */
public record SlamHitPayload(int entityId, Vec3 position) implements CustomPacketPayload {
	public static final Type<SlamHitPayload> TYPE = new Type<>(Ultracraft.id("slam_hit"));
	public static final StreamCodec<ByteBuf, SlamHitPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, SlamHitPayload::entityId,
		Vec3.STREAM_CODEC, SlamHitPayload::position,
		SlamHitPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
