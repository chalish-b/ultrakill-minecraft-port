package dev.ultracraft.net;

import dev.ultracraft.Ultracraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * Client to server: the local player hit an enemy with a push, a cannonball's or the Jackhammer's.
 *
 * @param damage ULTRAKILL damage
 * @param push the enemy's knockback, in units per second
 * @param position where it hit
 */
public record KnockbackHitPayload(int entityId, float damage, Vec3 push, Vec3 position) implements CustomPacketPayload {
	public static final Type<KnockbackHitPayload> TYPE = new Type<>(Ultracraft.id("knockback_hit"));
	public static final StreamCodec<ByteBuf, KnockbackHitPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, KnockbackHitPayload::entityId,
		ByteBufCodecs.FLOAT, KnockbackHitPayload::damage,
		Vec3.STREAM_CODEC, KnockbackHitPayload::push,
		Vec3.STREAM_CODEC, KnockbackHitPayload::position,
		KnockbackHitPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
