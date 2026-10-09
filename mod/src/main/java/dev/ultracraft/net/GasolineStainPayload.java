package dev.ultracraft.net;

import dev.ultracraft.Ultracraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/** Client to server: a drop of the local player's gasoline landed at {@code position}, on a surface facing {@code face}. */
public record GasolineStainPayload(Vec3 position, Direction face) implements CustomPacketPayload {
	public static final Type<GasolineStainPayload> TYPE = new Type<>(Ultracraft.id("gasoline_stain"));
	public static final StreamCodec<ByteBuf, GasolineStainPayload> CODEC = StreamCodec.composite(
		Vec3.STREAM_CODEC, GasolineStainPayload::position,
		Direction.STREAM_CODEC, GasolineStainPayload::face,
		GasolineStainPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
