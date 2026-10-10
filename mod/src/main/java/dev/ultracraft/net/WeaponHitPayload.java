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
public record WeaponHitPayload(int entityId, float damage, boolean head, Vec3 position, Kind kind) implements CustomPacketPayload {
	public static final Type<WeaponHitPayload> TYPE = new Type<>(Ultracraft.id("weapon_hit"));
	public static final StreamCodec<ByteBuf, WeaponHitPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, WeaponHitPayload::entityId,
		ByteBufCodecs.FLOAT, WeaponHitPayload::damage,
		ByteBufCodecs.BOOL, WeaponHitPayload::head,
		Vec3.STREAM_CODEC, WeaponHitPayload::position,
		ByteBufCodecs.idMapper(i -> Kind.values()[i], Kind::ordinal), WeaponHitPayload::kind,
		WeaponHitPayload::new
	);

	public WeaponHitPayload(int entityId, float damage, boolean head, Vec3 position) {
		this(entityId, damage, head, position, Kind.HIT);
	}

	public enum Kind {
		HIT,
		/** A slab revolver's shot: an enemy it kills bursts. */
		HIT_BURSTING,
		/** A zone's hit on the body (shotgunzone, hammerzone): it only lands if it kills. */
		ZONE,
		/** A tick of the Screwdriver's drill: the enemy doesn't flinch, and its blood is twice the size. */
		DRILL,
		/** The drill punched out of an enemy: an enemy it kills bursts. */
		DRILL_PUNCH,
		/** A nail: the enemy isn't knocked back, and flinches no more often than Minecraft's damage cooldown allows. */
		NAIL,
		/** A nail bomb's nail: as a nail, and an enemy it kills bursts. */
		NAIL_BOMB,
		/** A zap: an enemy it kills bursts. */
		ZAP,
		/** The zapper's zap on an enemy that conducts it: it leaves the enemy alive, for the aftershock to come. */
		ZAP_CONDUCTING,
		/** No damage: a nail coming out of the enemy as it conducts leaves a little blood. */
		BLEED
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
