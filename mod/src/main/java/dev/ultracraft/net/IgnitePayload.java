package dev.ultracraft.net;

import dev.ultracraft.Ultracraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * Client to server: one of the local player's explosions went off, lighting the gasoline around it
 * (StainVoxelManager.TryIgniteAt with {@code checkSize}).
 */
public record IgnitePayload(Vec3 at, int checkSize) implements CustomPacketPayload {
	public static final Type<IgnitePayload> TYPE = new Type<>(Ultracraft.id("ignite"));
	public static final StreamCodec<ByteBuf, IgnitePayload> CODEC = StreamCodec.composite(
		Vec3.STREAM_CODEC, IgnitePayload::at,
		ByteBufCodecs.VAR_INT, IgnitePayload::checkSize,
		IgnitePayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
