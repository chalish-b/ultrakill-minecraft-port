package dev.ultracraft.net;

import dev.ultracraft.Ultracraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: a drop of the local player's gasoline hit an enemy, which soaks it up. */
public record GasolineSoakPayload(int entityId) implements CustomPacketPayload {
	public static final Type<GasolineSoakPayload> TYPE = new Type<>(Ultracraft.id("gasoline_soak"));
	public static final StreamCodec<ByteBuf, GasolineSoakPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, GasolineSoakPayload::entityId,
		GasolineSoakPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
