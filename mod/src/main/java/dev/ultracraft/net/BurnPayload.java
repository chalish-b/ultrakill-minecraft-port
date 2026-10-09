package dev.ultracraft.net;

import dev.ultracraft.Ultracraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: something of the local player's that sets things alight (a heated nail, an explosion that
 * ignites) hit an enemy. It burns if it's soaked in gasoline.
 */
public record BurnPayload(int entityId) implements CustomPacketPayload {
	public static final Type<BurnPayload> TYPE = new Type<>(Ultracraft.id("burn"));
	public static final StreamCodec<ByteBuf, BurnPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, BurnPayload::entityId,
		BurnPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
