package dev.ultracraft.net;

import dev.ultracraft.Ultracraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * Client to server: one of the local player's explosions reached an entity.
 *
 * @param damage ULTRAKILL damage, for an enemy
 * @param push units per second: an enemy's knockback, a projectile's new direction at no less than that speed,
 *     or anything else's new velocity
 */
public record ExplosionHitPayload(int entityId, float damage, Vec3 push) implements CustomPacketPayload {
	public static final Type<ExplosionHitPayload> TYPE = new Type<>(Ultracraft.id("explosion_hit"));
	public static final StreamCodec<ByteBuf, ExplosionHitPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, ExplosionHitPayload::entityId,
		ByteBufCodecs.FLOAT, ExplosionHitPayload::damage,
		Vec3.STREAM_CODEC, ExplosionHitPayload::push,
		ExplosionHitPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
