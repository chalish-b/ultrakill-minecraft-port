package dev.ultracraft.net;

import dev.ultracraft.Ultracraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * Client to server: what the local player's Whiplash did (HookArm).
 *
 * @param entityId the enemy or item it's on
 * @param position where it caught the enemy
 * @param scale blocks a tick for one unit a second, with the player's speed scale: how pulled things move
 */
public record WhiplashPayload(Action action, int entityId, Vec3 position, double scale) implements CustomPacketPayload {
	public static final Type<WhiplashPayload> TYPE = new Type<>(Ultracraft.id("whiplash"));
	public static final StreamCodec<ByteBuf, WhiplashPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.idMapper(i -> Action.values()[i], Action::ordinal), WhiplashPayload::action,
		ByteBufCodecs.VAR_INT, WhiplashPayload::entityId,
		Vec3.STREAM_CODEC, WhiplashPayload::position,
		ByteBufCodecs.DOUBLE, WhiplashPayload::scale,
		WhiplashPayload::new
	);

	public static WhiplashPayload of(Action action) {
		return new WhiplashPayload(action, -1, Vec3.ZERO, 0);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public enum Action {
		/** The hook caught an enemy, which it hits. */
		HOOK,
		/** The hooked enemy is light: it's pulled to the player. */
		PULL_ENEMY,
		/** The hooked enemy is heavy: the player is pulled to it. */
		PULL_PLAYER,
		/** A light enemy being pulled has reached the player. */
		ARRIVED,
		/** The hook let go. */
		RELEASE,
		/** The hook caught an item, which comes to the player. */
		GRAB
	}
}
