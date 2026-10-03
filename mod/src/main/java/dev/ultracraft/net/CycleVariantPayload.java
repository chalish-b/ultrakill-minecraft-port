package dev.ultracraft.net;

import dev.ultracraft.Ultracraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: switch the held weapon to its next variant. */
public record CycleVariantPayload() implements CustomPacketPayload {
	public static final Type<CycleVariantPayload> TYPE = new Type<>(Ultracraft.id("cycle_variant"));
	public static final StreamCodec<ByteBuf, CycleVariantPayload> CODEC = StreamCodec.unit(new CycleVariantPayload());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
