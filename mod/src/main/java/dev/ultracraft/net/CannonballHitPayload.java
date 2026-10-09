package dev.ultracraft.net;

import dev.ultracraft.Ultracraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * Client to server: the local player's cannonball hit an enemy.
 *
 * @param damage ULTRAKILL damage
 * @param push the enemy's knockback, in units per second
 * @param position where it hit
 */
public record CannonballHitPayload(int entityId, float damage, Vec3 push, Vec3 position) implements CustomPacketPayload {
	public static final Type<CannonballHitPayload> TYPE = new Type<>(Ultracraft.id("cannonball_hit"));
	public static final StreamCodec<ByteBuf, CannonballHitPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, CannonballHitPayload::entityId,
		ByteBufCodecs.FLOAT, CannonballHitPayload::damage,
		Vec3.STREAM_CODEC, CannonballHitPayload::push,
		Vec3.STREAM_CODEC, CannonballHitPayload::position,
		CannonballHitPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
