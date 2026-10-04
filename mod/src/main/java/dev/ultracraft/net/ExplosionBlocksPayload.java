package dev.ultracraft.net;

import dev.ultracraft.Ultracraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * Client to server: one of the local player's explosions went off, breaking blocks as a Minecraft explosion of
 * {@code power} would.
 */
public record ExplosionBlocksPayload(Vec3 center, float power) implements CustomPacketPayload {
	public static final Type<ExplosionBlocksPayload> TYPE = new Type<>(Ultracraft.id("explosion_blocks"));
	public static final StreamCodec<ByteBuf, ExplosionBlocksPayload> CODEC = StreamCodec.composite(
		Vec3.STREAM_CODEC, ExplosionBlocksPayload::center,
		ByteBufCodecs.FLOAT, ExplosionBlocksPayload::power,
		ExplosionBlocksPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
