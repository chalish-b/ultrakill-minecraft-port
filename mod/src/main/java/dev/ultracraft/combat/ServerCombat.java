package dev.ultracraft.combat;

import dev.ultracraft.PlayerShape;
import dev.ultracraft.UltraItems;
import dev.ultracraft.UltraPlayer;
import dev.ultracraft.mixin.AbstractArrowAccessor;
import dev.ultracraft.net.CycleVariantPayload;
import dev.ultracraft.net.PunchPayload;
import dev.ultracraft.net.PunchResultPayload;
import dev.ultracraft.net.WeaponHitPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The server's side of ULTRAKILL combat: applying the client's weapon hits, punches and parries, blood that
 * heals, and the parry's hitstop.
 */
public final class ServerCombat {
	/** Minecraft health per point of ULTRAKILL damage. */
	public static final float HEALTH_PER_DAMAGE = 10;
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	/** The Feedbacker reaches 4 units in front of the camera (Punch's raycasts). */
	private static final double PUNCH_REACH = 4 * S;
	private static final double PUNCH_RADIUS = 1 * S;
	/** How long a punch can still connect after it's thrown, like the animation's active frames. */
	private static final int PUNCH_ACTIVE_TICKS = 4;
	private static final float PUNCH_DAMAGE = 1;
	private static final long HITSTOP_MILLIS = 250;

	private static final Map<UUID, Integer> PUNCHES = new HashMap<>();
	private static boolean hitstopFroze;
	private static long hitstopEnd;

	private ServerCombat() {
	}

	public static void init() {
		ServerPlayNetworking.registerGlobalReceiver(WeaponHitPayload.TYPE, (payload, context) -> onWeaponHit(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(PunchPayload.TYPE, (payload, context) -> onPunch(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(CycleVariantPayload.TYPE, (payload, context) -> onCycleVariant(context.player()));
		ServerTickEvents.END_SERVER_TICK.register(ServerCombat::tick);
	}

	private static boolean active(ServerPlayer player) {
		return ((UltraPlayer)player).ultracraft$isActive() && player.isAlive();
	}

	private static void onWeaponHit(ServerPlayer player, WeaponHitPayload hit) {
		if (!active(player) || !(player.level().getEntity(hit.entityId()) instanceof LivingEntity target) || target == player || !target.isAlive()) {
			return;
		}
		if (target.distanceToSqr(player) > 512 * 512) {
			return;
		}
		damage(player, target, hit.damage(), hit.head(), hit.position());
	}

	/** EnemyIdentifier.DeliverDamage: no invulnerability frames, and the blood it spills heals. */
	private static void damage(ServerPlayer player, LivingEntity target, float damage, boolean head, Vec3 position) {
		ServerLevel level = player.level();
		DamageSource source = player.damageSources().playerAttack(player);
		target.setInvulnerableTime(0);
		target.hurtServer(level, source, damage * HEALTH_PER_DAMAGE);
		BloodZones.add(level, position, Gore.of(head, damage, !target.isAlive()));
	}

	private static void onCycleVariant(ServerPlayer player) {
		ItemStack held = player.getMainHandItem();
		Item next = UltraItems.nextVariant(held);
		if (next != null) {
			player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(next, held.getCount()));
		}
	}

	private static void onPunch(ServerPlayer player) {
		if (!active(player)) {
			return;
		}
		if (!tryPunch(player)) {
			PUNCHES.put(player.getUUID(), PUNCH_ACTIVE_TICKS);
		}
	}

	private static void tick(MinecraftServer server) {
		for (Iterator<Map.Entry<UUID, Integer>> it = PUNCHES.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Integer> punch = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(punch.getKey());
			if (player == null || !active(player) || tryPunch(player) || punch.getValue() <= 1) {
				it.remove();
			} else {
				punch.setValue(punch.getValue() - 1);
			}
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (((UltraPlayer)player).ultracraft$isActive()) {
				UltraHealth.tick(player, 0.05F);
			}
		}
		BloodZones.tick(server);
		if (hitstopFroze && Util.getMillis() >= hitstopEnd) {
			server.tickRateManager().setFrozen(false);
			hitstopFroze = false;
		}
	}

	/** One active frame of a punch: parries a projectile in reach, or else hits an enemy. */
	private static boolean tryPunch(ServerPlayer player) {
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getLookAngle().scale(PUNCH_REACH));
		AABB area = new AABB(eye, end).inflate(PUNCH_RADIUS + 1);

		for (Projectile projectile : player.level().getEntitiesOfClass(Projectile.class, area, p -> p.isAlive() && p.getOwner() != player)) {
			if (reaches(projectile.getBoundingBox().inflate(PUNCH_RADIUS), eye, end)) {
				parry(player, projectile);
				return true;
			}
		}

		LivingEntity target = null;
		Vec3 hitPoint = null;
		double nearest = Double.MAX_VALUE;
		for (LivingEntity entity : player.level().getEntitiesOfClass(LivingEntity.class, area, e -> e != player && e.isAlive() && !e.isSpectator())) {
			AABB box = entity.getBoundingBox().inflate(PUNCH_RADIUS);
			Optional<Vec3> clip = box.contains(eye) ? Optional.of(eye) : box.clip(eye, end);
			if (clip.isPresent() && clip.get().distanceToSqr(eye) < nearest) {
				nearest = clip.get().distanceToSqr(eye);
				target = entity;
				hitPoint = entity.getBoundingBox().getCenter();
			}
		}
		if (target == null) {
			return false;
		}
		damage(player, target, PUNCH_DAMAGE, false, hitPoint);
		float yaw = player.getYRot() * Mth.DEG_TO_RAD;
		target.knockback(0.6, Mth.sin(yaw), -Mth.cos(yaw), player.damageSources().playerAttack(player), PUNCH_DAMAGE);
		ServerPlayNetworking.send(player, new PunchResultPayload(PunchResultPayload.Result.ENEMY, hitPoint));
		return true;
	}

	private static boolean reaches(AABB box, Vec3 from, Vec3 to) {
		return box.contains(from) || box.clip(from, to).isPresent();
	}

	/** Punch.ParryProjectile: sends it back where the player looks, harder, and heals the player fully. */
	private static void parry(ServerPlayer player, Projectile projectile) {
		double speed = Math.max(projectile.getDeltaMovement().length() * 2, 2.5);
		projectile.deflect(ProjectileDeflection.AIM_DEFLECT, player, EntityReference.<Entity>of(player), true, speed);
		if (projectile instanceof AbstractArrow arrow) {
			arrow.setBaseDamage(((AbstractArrowAccessor)arrow).ultracraft$getBaseDamage() * 2.5);
		}
		UltraHealth.heal(player, UltraHealth.MAX_HP);
		hitstop(player.level().getServer());
		ServerPlayNetworking.send(player, new PunchResultPayload(PunchResultPayload.Result.PARRY, projectile.position()));
	}

	/** TimeController.TrueStop: the world stops for a quarter of a second. */
	private static void hitstop(MinecraftServer server) {
		ServerTickRateManager rates = server.tickRateManager();
		if (!rates.isFrozen()) {
			rates.setFrozen(true);
			hitstopFroze = true;
		}
		if (hitstopFroze) {
			hitstopEnd = Util.getMillis() + HITSTOP_MILLIS;
		}
	}
}
