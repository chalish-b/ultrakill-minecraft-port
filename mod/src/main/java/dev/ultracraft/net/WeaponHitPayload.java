package dev.ultracraft.net;

import dev.ultracraft.Ultracraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * Client to server: the local player's weapon hit an entity.
 *
 * @param damage ULTRAKILL damage, with any headshot bonus already applied
 * @param head whether it hit the head, for the blood it leaves
 */
public record WeaponHitPayload(int entityId, float damage, boolean head, Vec3 position) implements CustomPacketPayload {
	public static final Type<WeaponHitPayload> TYPE = new Type<>(Ultracraft.id("weapon_hit"));
	public static final StreamCodec<ByteBuf, WeaponHitPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, WeaponHitPayload::entityId,
		ByteBufCodecs.FLOAT, WeaponHitPayload::damage,
		ByteBufCodecs.BOOL, WeaponHitPayload::head,
		Vec3.STREAM_CODEC, WeaponHitPayload::position,
		WeaponHitPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
