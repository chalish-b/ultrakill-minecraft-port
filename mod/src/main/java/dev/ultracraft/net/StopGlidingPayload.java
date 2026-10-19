package dev.ultracraft.net;

import dev.ultracraft.Ultracraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the local player stopped gliding with their elytra before landing. */
public record StopGlidingPayload() implements CustomPacketPayload {
	public static final Type<StopGlidingPayload> TYPE = new Type<>(Ultracraft.id("stop_gliding"));
	public static final StreamCodec<ByteBuf, StopGlidingPayload> CODEC = StreamCodec.unit(new StopGlidingPayload());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
