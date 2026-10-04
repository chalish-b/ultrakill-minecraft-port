package dev.ultracraft.net;

import dev.ultracraft.Ultracraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * Client to server: one of the local player's magnets pulled an enemy with nails in it.
 *
 * @param change units per second added to its velocity
 */
public record MagnetPullPayload(int entityId, Vec3 change) implements CustomPacketPayload {
	public static final Type<MagnetPullPayload> TYPE = new Type<>(Ultracraft.id("magnet_pull"));
	public static final StreamCodec<ByteBuf, MagnetPullPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, MagnetPullPayload::entityId,
		Vec3.STREAM_CODEC, MagnetPullPayload::change,
		MagnetPullPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
