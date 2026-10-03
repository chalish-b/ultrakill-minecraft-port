package dev.ultracraft.net;

import dev.ultracraft.Ultracraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the local player's {@link dev.ultracraft.UltraPlayer} state changed. */
public record UltraStatePayload(boolean active, boolean crouched) implements CustomPacketPayload {
	public static final Type<UltraStatePayload> TYPE = new Type<>(Ultracraft.id("state"));
	public static final StreamCodec<ByteBuf, UltraStatePayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.BOOL, UltraStatePayload::active,
		ByteBufCodecs.BOOL, UltraStatePayload::crouched,
		UltraStatePayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
