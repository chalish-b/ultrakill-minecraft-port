package dev.ultracraft.net;

import dev.ultracraft.Ultracraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the local player punched with the Feedbacker. */
public record PunchPayload() implements CustomPacketPayload {
	public static final Type<PunchPayload> TYPE = new Type<>(Ultracraft.id("punch"));
	public static final StreamCodec<ByteBuf, PunchPayload> CODEC = StreamCodec.unit(new PunchPayload());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
