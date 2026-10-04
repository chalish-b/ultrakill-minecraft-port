package dev.ultracraft.combat;

import dev.ultracraft.PlayerShape;
import dev.ultracraft.UltraPlayer;
import dev.ultracraft.net.WhiplashBrokenPayload;
import dev.ultracraft.net.WhiplashPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The server's side of the Whiplash (HookArm): the hit when it catches an enemy, a light enemy or an item coming
 * to the player, and the hard damage of pulling or being pulled to an enemy. An enemy that teleports knocks the hook
 * off.
 */
public final class ServerWhiplash {
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	/** The hook's hit on the enemy it catches. */
	private static final float HOOK_DAMAGE = 0.2F;
	/** A light enemy comes to the player at this speed (units a second). */
	private static final double PULL_SPEED = 60;
	/** It has reached the player once it's this close to them (units). */
	private static final double ARRIVE_DISTANCE = 0.25;
	/** A walking enemy that reaches the player in the air is let go rising at this speed (units a second). */
	private static final double ARRIVE_RISE = 15;
	/** An item comes to the player with the hook, at this speed plus half the distance it was caught at (at least 25 units). */
	private static final double RETURN_SPEED = 100;
	private static final double MIN_RETURN_DISTANCE = 25;
	/** An item is with the player once it's this close to their eyes (blocks), where they pick it up. */
	private static final double ITEM_ARRIVED = 1;
	/** An item still on its way after this many ticks is let go. */
	private static final int ITEM_TICKS = 60;
	/** The hook's 300 units of chain, and some room: how far from the player it can catch anything (blocks). */
	private static final double REACH = 300 * S + 16;
	/** The player's speed scale is kept within this, as blocks a tick per unit a second. */
	private static final double MAX_SCALE = 10 * S / 20;

	private enum Mode {
		/** On an enemy, not pulling yet. */
		CAUGHT,
		PULL_ENEMY,
		PULL_PLAYER,
		GRAB
	}

	private static final class Hook {
		final int entityId;
		Mode mode;
		/** Blocks a tick for one unit a second. */
		double scale;
		/** Blocks a tick, for an item. */
		double speed;
		int ticks;

		Hook(int entityId, Mode mode) {
			this.entityId = entityId;
			this.mode = mode;
		}
	}

	private static final Map<UUID, Hook> HOOKS = new HashMap<>();

	private ServerWhiplash() {
	}

	public static void init() {
		ServerPlayNetworking.registerGlobalReceiver(WhiplashPayload.TYPE, (payload, context) -> onWhiplash(context.player(), payload));
		// Before the world ticks, so a pulled enemy moves this tick at the speed it's given.
		ServerTickEvents.START_SERVER_TICK.register(ServerWhiplash::tick);
	}

	private static void onWhiplash(ServerPlayer player, WhiplashPayload payload) {
		if (!ServerCombat.active(player)) {
			return;
		}
		UUID id = player.getUUID();
		Hook hook = HOOKS.get(id);
		Entity entity = player.level().getEntity(payload.entityId());
		double scale = Math.clamp(payload.scale(), 0, MAX_SCALE);
		switch (payload.action()) {
			case HOOK -> {
				if (entity instanceof LivingEntity target && target != player && target.isAlive() && target.distanceTo(player) <= REACH) {
					ServerCombat.damage(player, target, HOOK_DAMAGE, false, payload.position(), false, false);
					HOOKS.put(id, new Hook(target.getId(), Mode.CAUGHT));
				}
			}
			case PULL_ENEMY, PULL_PLAYER -> {
				if (hook != null && hook.entityId == payload.entityId()) {
					hook.mode = payload.action() == WhiplashPayload.Action.PULL_ENEMY ? Mode.PULL_ENEMY : Mode.PULL_PLAYER;
					hook.scale = scale;
				}
			}
			case ARRIVED -> {
				if (hook != null && hook.mode == Mode.PULL_ENEMY && player.level().getEntity(hook.entityId) instanceof LivingEntity enemy) {
					arrive(enemy, hook);
				}
				HOOKS.remove(id);
			}
			case RELEASE -> HOOKS.remove(id);
			case GRAB -> {
				if (entity instanceof ItemEntity item && item.isAlive() && item.distanceTo(player) <= REACH) {
					Hook grab = new Hook(item.getId(), Mode.GRAB);
					double distance = Math.max(item.position().distanceTo(player.getEyePosition()) / S, MIN_RETURN_DISTANCE);
					grab.speed = (RETURN_SPEED + distance / 2) * scale;
					item.setNoPickUpDelay();
					HOOKS.put(id, grab);
				}
			}
		}
	}

	private static void tick(MinecraftServer server) {
		if (!server.tickRateManager().runsNormally()) {
			return;
		}
		for (Iterator<Map.Entry<UUID, Hook>> it = HOOKS.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Hook> entry = it.next();
			Hook hook = entry.getValue();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			Entity entity = player == null ? null : player.level().getEntity(hook.entityId);
			if (player == null || !ServerCombat.active(player) || entity == null || !entity.isAlive()) {
				it.remove();
				continue;
			}
			boolean keep = switch (hook.mode) {
				case CAUGHT -> true;
				case PULL_ENEMY -> pullEnemy(player, (LivingEntity)entity, hook);
				case PULL_PLAYER -> true;
				case GRAB -> pullItem(player, entity, hook);
			};
			// HookArm: empty health turns into hard damage while the player pulls or is pulled to an enemy, out of water.
			if ((hook.mode == Mode.PULL_ENEMY || hook.mode == Mode.PULL_PLAYER) && !player.isInWater()) {
				UltraHealth.whiplash(player, 0.05F);
			}
			if (!keep) {
				it.remove();
			}
		}
	}

	/**
	 * HookArm.FixedUpdate on a light target: the enemy comes at the player's body (a flying one at their camera),
	 * facing them, until it touches them. It doesn't overshoot: a tick is longer than ULTRAKILL's physics step.
	 *
	 * @return whether it's still on its way
	 */
	private static boolean pullEnemy(ServerPlayer player, LivingEntity enemy, Hook hook) {
		AABB box = enemy.getBoundingBox();
		Vec3 center = box.getCenter();
		Vec3 near = closest(player.getBoundingBox(), center);
		double gap = near.distanceTo(closest(box, near));
		if (gap < ARRIVE_DISTANCE * S) {
			arrive(enemy, hook);
			return false;
		}
		boolean flying = WhiplashTargets.flying(enemy);
		Vec3 target = flying ? player.getEyePosition() : root(player);
		Vec3 direction = target.subtract(center).normalize();
		double speed = PULL_SPEED * hook.scale;
		double step = gap > speed ? speed : Math.max(gap - ARRIVE_DISTANCE * S / 2, 0);
		enemy.setDeltaMovement(direction.scale(step));
		enemy.needsSync = true;
		if (enemy instanceof Mob mob) {
			mob.getNavigation().stop();
		}
		Vec3 look = target.subtract(enemy.getEyePosition());
		float yaw = (float)Math.toDegrees(Math.atan2(-look.x, look.z));
		enemy.setYRot(yaw);
		enemy.setYHeadRot(yaw);
		enemy.setYBodyRot(yaw);
		if (flying) {
			enemy.setXRot((float)Math.toDegrees(-Math.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z))));
		}
		return true;
	}

	/** HookArm: a pulled enemy that reaches the player stops, or rises a little if it walks and is in the air. */
	private static void arrive(LivingEntity enemy, Hook hook) {
		boolean rises = !WhiplashTargets.flying(enemy) && !enemy.onGround() && !enemy.isUnderWater();
		enemy.setDeltaMovement(rises ? new Vec3(0, ARRIVE_RISE * hook.scale, 0) : Vec3.ZERO);
		enemy.needsSync = true;
	}

	/**
	 * The hook brings an item back to the player (where ULTRAKILL's arm takes hold of it), and they pick it up.
	 *
	 * @return whether it's still on its way
	 */
	private static boolean pullItem(ServerPlayer player, Entity item, Hook hook) {
		Vec3 toPlayer = player.getEyePosition().subtract(item.position());
		double distance = toPlayer.length();
		if (distance < ITEM_ARRIVED || ++hook.ticks > ITEM_TICKS) {
			item.setDeltaMovement(Vec3.ZERO);
			item.needsSync = true;
			return false;
		}
		item.setDeltaMovement(toPlayer.scale(Math.min(hook.speed, distance) / distance));
		item.needsSync = true;
		return true;
	}

	/** An enemy teleported: the hook on it, if any, comes off. */
	public static void teleported(Entity entity) {
		for (Iterator<Map.Entry<UUID, Hook>> it = HOOKS.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Hook> entry = it.next();
			Hook hook = entry.getValue();
			if (hook.entityId != entity.getId() || hook.mode == Mode.GRAB || entity.level().getServer() == null) {
				continue;
			}
			ServerPlayer player = entity.level().getServer().getPlayerList().getPlayer(entry.getKey());
			if (player != null) {
				ServerPlayNetworking.send(player, new WhiplashBrokenPayload());
			}
			it.remove();
		}
	}

	/** NewMovement's transform.position. */
	private static Vec3 root(ServerPlayer player) {
		double height = ((UltraPlayer)player).ultracraft$isCrouched() ? PlayerShape.CROUCHED_ROOT : PlayerShape.STANDING_ROOT;
		return player.position().add(0, height * S, 0);
	}

	private static Vec3 closest(AABB box, Vec3 point) {
		return new Vec3(Math.clamp(point.x, box.minX, box.maxX), Math.clamp(point.y, box.minY, box.maxY), Math.clamp(point.z, box.minZ, box.maxZ));
	}
}
