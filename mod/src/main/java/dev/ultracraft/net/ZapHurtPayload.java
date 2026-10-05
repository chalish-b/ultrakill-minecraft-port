package dev.ultracraft.net;

import dev.ultracraft.Ultracraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: a zap went through the water the local player is in. */
public record ZapHurtPayload() implements CustomPacketPayload {
	public static final Type<ZapHurtPayload> TYPE = new Type<>(Ultracraft.id("zap_hurt"));
	public static final StreamCodec<ByteBuf, ZapHurtPayload> CODEC = StreamCodec.unit(new ZapHurtPayload());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
