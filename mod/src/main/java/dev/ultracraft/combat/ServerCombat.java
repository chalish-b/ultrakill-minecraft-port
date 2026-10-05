package dev.ultracraft.combat;

import dev.ultracraft.PlayerShape;
import dev.ultracraft.UltraDifficulty;
import dev.ultracraft.Ultracraft;
import dev.ultracraft.UltraItems;
import dev.ultracraft.UltraPlayer;
import dev.ultracraft.mixin.AbstractArrowAccessor;
import dev.ultracraft.mixin.LivingEntityAccessor;
import dev.ultracraft.net.CycleVariantPayload;
import dev.ultracraft.net.ExplosionHitPayload;
import dev.ultracraft.net.ExplosionHurtPayload;
import dev.ultracraft.net.HitstopPayload;
import dev.ultracraft.net.MagnetPullPayload;
import dev.ultracraft.net.PunchPayload;
import dev.ultracraft.net.PunchResultPayload;
import dev.ultracraft.net.SlamHitPayload;
import dev.ultracraft.net.WeaponHitPayload;
import dev.ultracraft.net.ZapHurtPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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
	/** A drill's blood splatters are this much bigger. */
	public static final float DRILL_BLOOD_SCALE = 2;
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	/** The Feedbacker reaches 4 units in front of the camera (Punch's raycasts). */
	private static final double PUNCH_REACH = 4 * S;
	private static final double PUNCH_RADIUS = 1 * S;
	/** How long a punch can still connect after it's thrown, like the animation's active frames. */
	private static final int PUNCH_ACTIVE_TICKS = 4;
	/** TimeController.TrueStop after a parry. */
	private static final long PARRY_HITSTOP_MILLIS = 250;
	/** TimeController.HitStop after a punch lands. */
	private static final long PUNCH_HITSTOP_MILLIS = 100;
	/** A slam's hit, and its push down: DeliverDamage's force of 50000 comes to this many units a second. */
	private static final float SLAM_DAMAGE = 2;
	private static final double SLAM_PUSH = 50;
	/** How far from the player a slam can reach an enemy (blocks). */
	private static final double SLAM_REACH = 8;
	/** LivingEntity's damage cooldown after a hit (ticks); hit with more of it left than this, an entity doesn't react. */
	private static final int DAMAGE_COOLDOWN = 20;
	private static final int DAMAGE_COOLDOWN_REACTS = 10;
	/** The health the zapper leaves an enemy that conducts its zap. */
	private static final float CONDUCTOR_HEALTH = 0.001F;
	/** A zap through the water the player is in (EnemyIdentifier.Zap), as damage that never scales with difficulty. */
	private static final ResourceKey<DamageType> ZAP = ResourceKey.create(Registries.DAMAGE_TYPE, Ultracraft.id("zap"));
	private static final int ZAP_HURT = 50;
	/** How far from the player an explosion can reach something. */
	private static final double EXPLOSION_REACH = 64;
	/**
	 * The player's own explosions: vanilla's player explosion, without the knock that would undo their launch, and
	 * the same on every difficulty.
	 */
	private static final ResourceKey<DamageType> EXPLOSION = ResourceKey.create(Registries.DAMAGE_TYPE, Ultracraft.id("explosion"));

	private static final Map<UUID, ActivePunch> PUNCHES = new HashMap<>();
	private static boolean hitstopFroze;
	private static long hitstopEnd;

	private ServerCombat() {
	}

	public static void init() {
		ServerPlayNetworking.registerGlobalReceiver(WeaponHitPayload.TYPE, (payload, context) -> onWeaponHit(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(PunchPayload.TYPE, (payload, context) -> onPunch(context.player(), payload.fist()));
		ServerPlayNetworking.registerGlobalReceiver(CycleVariantPayload.TYPE, (payload, context) -> onCycleVariant(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(ExplosionHitPayload.TYPE, (payload, context) -> onExplosionHit(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(ExplosionHurtPayload.TYPE, (payload, context) -> onExplosionHurt(context.player(), payload.damage()));
		ServerPlayNetworking.registerGlobalReceiver(SlamHitPayload.TYPE, (payload, context) -> onSlamHit(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(MagnetPullPayload.TYPE, (payload, context) -> onMagnetPull(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(ZapHurtPayload.TYPE, (payload, context) -> onZapHurt(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(HitstopPayload.TYPE, (payload, context) -> {
			if (active(context.player())) {
				hitstop(context.player().level().getServer(), Math.clamp(payload.millis(), 0, PARRY_HITSTOP_MILLIS));
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(ServerCombat::tick);
	}

	static boolean active(ServerPlayer player) {
		return ((UltraPlayer)player).ultracraft$isActive() && player.isAlive();
	}

	/** GroundCheck: a slam coming down on an enemy hits it for 2, bursting it if it dies, and knocks it down. */
	private static void onSlamHit(ServerPlayer player, SlamHitPayload hit) {
		if (!active(player) || !(player.level().getEntity(hit.entityId()) instanceof LivingEntity target) || target == player || !target.isAlive()) {
			return;
		}
		if (target.distanceToSqr(player) > SLAM_REACH * SLAM_REACH) {
			return;
		}
		Vec3 velocity = target.getDeltaMovement();
		damage(player, target, SLAM_DAMAGE, false, hit.position(), true, false);
		knockBack(target, velocity, new Vec3(0, -SLAM_PUSH, 0));
	}

	private static void onWeaponHit(ServerPlayer player, WeaponHitPayload hit) {
		if (!active(player) || !(player.level().getEntity(hit.entityId()) instanceof LivingEntity target) || target == player) {
			return;
		}
		if (target.distanceToSqr(player) > 512 * 512) {
			return;
		}
		if (!target.isAlive()) {
			// A drill keeps a corpse bleeding while it's still in it.
			if (hit.kind() == WeaponHitPayload.Kind.DRILL) {
				BloodZones.add(player.level(), hit.position(), Gore.SMALL, false, DRILL_BLOOD_SCALE);
			}
			if (hit.kind() == WeaponHitPayload.Kind.BLEED) {
				BloodZones.add(player.level(), hit.position(), Gore.SMALL, false);
			}
			return;
		}
		switch (hit.kind()) {
			case HIT -> damage(player, target, hit.damage(), hit.head(), hit.position(), false, false);
			case DRILL -> drillDamage(player, target, hit.damage(), hit.position());
			case DRILL_PUNCH -> damage(player, target, hit.damage(), hit.head(), hit.position(), true, false);
			case NAIL -> nailDamage(player, target, hit.damage(), hit.position(), false);
			case NAIL_BOMB -> nailDamage(player, target, hit.damage(), hit.position(), true);
			case ZAP -> damage(player, target, hit.damage(), false, hit.position(), true, false);
			case ZAP_CONDUCTING -> {
				// EnemyIdentifier.DeliverDamage: the zapper leaves a conducting enemy a thousandth of a point of health.
				float left = (target.getHealth() + target.getAbsorptionAmount()) / HEALTH_PER_DAMAGE - CONDUCTOR_HEALTH;
				float damage = Math.min(hit.damage(), left);
				if (damage > 0) {
					damage(player, target, damage, false, hit.position(), true, false);
				}
			}
			case BLEED -> BloodZones.add(player.level(), hit.position(), Gore.SMALL, false);
		}
	}

	/**
	 * A nail (Nail.DamageEnemy): ULTRAKILL's nails don't knock enemies back. They come many a second, so one that
	 * lands within the enemy's damage cooldown, and doesn't kill it, does its full damage without the reaction.
	 */
	private static void nailDamage(ServerPlayer player, LivingEntity target, float damage, Vec3 position, boolean tryForExplode) {
		ServerLevel level = player.level();
		if (boss(target) && UltraDifficulty.of(level.getDifficulty()) >= UltraDifficulty.BRUTAL) {
			damage /= 1.5F;
		}
		Vec3 velocity = target.getDeltaMovement();
		float health = damage * HEALTH_PER_DAMAGE;
		DamageSource source = player.damageSources().playerAttack(player);
		if (target.damageCooldownTime > DAMAGE_COOLDOWN_REACTS && health < target.getHealth() + target.getAbsorptionAmount()) {
			quietHurt(level, source, target, health);
		} else {
			target.damageCooldownTime = 0;
			target.hurtServer(level, source, health);
		}
		target.setDeltaMovement(velocity);
		target.needsSync = true;
		boolean killed = !target.isAlive();
		BloodZones.add(level, position, tryForExplode && killed ? Gore.HEAD : Gore.of(false, damage, killed), false);
	}

	/** A magnet's pull on an enemy with nails in it. */
	private static void onMagnetPull(ServerPlayer player, MagnetPullPayload pull) {
		if (!active(player) || !(player.level().getEntity(pull.entityId()) instanceof LivingEntity target) || target == player || !target.isAlive()) {
			return;
		}
		if (target.distanceToSqr(player) > EXPLOSION_REACH * EXPLOSION_REACH) {
			return;
		}
		target.setDeltaMovement(target.getDeltaMovement().add(pull.change().scale(S / 20)));
		target.needsSync = true;
	}

	/**
	 * A tick of the Screwdriver's drill (Harpoon): unless it kills, the enemy takes it without flinching, crying out
	 * or being knocked back. Its blood is twice the size (Enemy.GetHurt scales a drill's).
	 */
	private static void drillDamage(ServerPlayer player, LivingEntity target, float damage, Vec3 position) {
		ServerLevel level = player.level();
		if (boss(target) && UltraDifficulty.of(level.getDifficulty()) >= UltraDifficulty.BRUTAL) {
			damage /= 1.5F;
		}
		float health = damage * HEALTH_PER_DAMAGE;
		DamageSource source = player.damageSources().playerAttack(player);
		if (health >= target.getHealth() + target.getAbsorptionAmount()) {
			target.damageCooldownTime = 0;
			target.hurtServer(level, source, health);
		} else {
			quietHurt(level, source, target, health);
		}
		BloodZones.add(level, position, Gore.of(false, damage, !target.isAlive()), false, DRILL_BLOOD_SCALE);
	}

	/**
	 * A hit that doesn't kill, taken without any reaction: within its damage cooldown, an entity takes damage past
	 * what it last took without flinching, crying out or being knocked back. Its cooldown and last hit are left as
	 * they were.
	 */
	private static void quietHurt(ServerLevel level, DamageSource source, LivingEntity target, float health) {
		LivingEntityAccessor accessor = (LivingEntityAccessor)target;
		int cooldown = target.damageCooldownTime;
		float lastHurt = accessor.ultracraft$getLastHurt();
		target.damageCooldownTime = DAMAGE_COOLDOWN;
		accessor.ultracraft$setLastHurt(0);
		target.hurtServer(level, source, health);
		target.damageCooldownTime = cooldown;
		accessor.ultracraft$setLastHurt(lastHurt);
	}

	/**
	 * EnemyIdentifier.DeliverDamage: no invulnerability frames, and the blood it spills heals. Bosses take two
	 * thirds of it on Brutal. With tryForExplode, an enemy it kills bursts (EnemyIdentifier.Explode): its head and
	 * limbs come off, leaving the
	 * head's blood.
	 *
	 * @return the blood it left
	 */
	static Gore damage(ServerPlayer player, LivingEntity target, float damage, boolean head, Vec3 position, boolean tryForExplode, boolean fromExplosion) {
		ServerLevel level = player.level();
		DamageSource source = player.damageSources().playerAttack(player);
		if (boss(target) && UltraDifficulty.of(level.getDifficulty()) >= UltraDifficulty.BRUTAL) {
			damage /= 1.5F;
		}
		target.damageCooldownTime = 0;
		target.hurtServer(level, source, damage * HEALTH_PER_DAMAGE);
		boolean killed = !target.isAlive();
		Gore gore = tryForExplode && killed ? Gore.HEAD : Gore.of(head, damage, killed);
		BloodZones.add(level, position, gore, fromExplosion);
		return gore;
	}

	/**
	 * Explosion.Collide, for what the client's explosion reached: an enemy takes the damage and the knockback, a
	 * projectile turns friendly and flies off along the push at no less than its speed, and anything else is sent
	 * off at the push's velocity.
	 */
	private static void onExplosionHit(ServerPlayer player, ExplosionHitPayload hit) {
		if (!active(player)) {
			return;
		}
		Entity entity = player.level().getEntity(hit.entityId());
		if (entity == null || entity == player || !entity.isAlive() || entity.distanceToSqr(player) > EXPLOSION_REACH * EXPLOSION_REACH) {
			return;
		}
		Vec3 push = hit.push();
		if (entity instanceof LivingEntity target) {
			Vec3 velocity = target.getDeltaMovement();
			if (hit.damage() > 0) {
				damage(player, target, hit.damage(), false, target.getBoundingBox().getCenter(), false, true);
			}
			knockBack(target, velocity, push);
		} else if (entity instanceof Projectile projectile) {
			Vec3 velocity = push.normalize().scale(Math.max(projectile.getDeltaMovement().length(), push.length() * S / 20));
			projectile.deflect((deflected, deflector, random, power) -> {
				deflected.setDeltaMovement(velocity);
				deflected.needsSync = true;
			}, player, EntityReference.of(player), false, Vec3.ZERO);
		} else {
			entity.setDeltaMovement(push.scale(S / 20));
			entity.needsSync = true;
		}
	}

	/** NewMovement.GetHurt from the player's own explosion. It leaves their velocity to their launch. */
	private static void onExplosionHurt(ServerPlayer player, int damage) {
		if (!active(player) || damage <= 0 || damage > 1000) {
			return;
		}
		player.hurtServer(player.level(), player.damageSources().source(EXPLOSION, player, player), damage / UltraHealth.HP_PER_HEALTH);
		UltraHealth.onExplosionHurt(player, damage);
	}

	/** NewMovement.GetHurt from a zap through the water the player is in. */
	private static void onZapHurt(ServerPlayer player) {
		if (active(player)) {
			player.hurtServer(player.level(), player.damageSources().source(ZAP), ZAP_HURT / UltraHealth.HP_PER_HEALTH);
		}
	}

	private static void onCycleVariant(ServerPlayer player) {
		ItemStack held = player.getMainHandItem();
		Item next = UltraItems.nextVariant(held);
		if (next != null) {
			player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(next, held.getCount()));
		}
	}

	private static void onPunch(ServerPlayer player, FistType type) {
		if (!active(player)) {
			return;
		}
		if (!tryPunch(player, type) && !punchBlock(player, type)) {
			PUNCHES.put(player.getUUID(), new ActivePunch(type, PUNCH_ACTIVE_TICKS));
		}
	}

	/** A punch that meets nothing else lands on the block in reach, if there is one, plants included. */
	private static boolean punchBlock(ServerPlayer player, FistType type) {
		Vec3 eye = player.getEyePosition();
		BlockHitResult block = player.level().clip(new ClipContext(eye, eye.add(player.getLookAngle().scale(PUNCH_REACH)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		if (block.getType() == HitResult.Type.MISS) {
			return false;
		}
		BlockDamage.hit(player, block.getBlockPos(), type.damage);
		return true;
	}

	/** A punch still looking for something to hit. */
	private static final class ActivePunch {
		final FistType type;
		int ticksLeft;

		ActivePunch(FistType type, int ticksLeft) {
			this.type = type;
			this.ticksLeft = ticksLeft;
		}
	}

	private static void tick(MinecraftServer server) {
		for (Iterator<Map.Entry<UUID, ActivePunch>> it = PUNCHES.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, ActivePunch> entry = it.next();
			ActivePunch punch = entry.getValue();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null || !active(player) || tryPunch(player, punch.type) || punch.ticksLeft <= 1) {
				it.remove();
			} else {
				punch.ticksLeft--;
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

	/** One active frame of a punch: the Feedbacker parries a projectile in reach; otherwise it hits an enemy. */
	private static boolean tryPunch(ServerPlayer player, FistType type) {
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		Vec3 end = eye.add(look.scale(PUNCH_REACH));
		AABB area = new AABB(eye, end).inflate(PUNCH_RADIUS + 1);

		if (type == FistType.STANDARD) {
			for (Projectile projectile : player.level().getEntitiesOfClass(Projectile.class, area, p -> p.isAlive() && p.getOwner() != player)) {
				if (reaches(projectile.getBoundingBox().inflate(PUNCH_RADIUS), eye, end)) {
					parry(player, projectile);
					return true;
				}
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
		Vec3 velocity = target.getDeltaMovement();
		Gore gore = damage(player, target, type.damage, false, hitPoint, type.tryForExplode, false);
		knockBack(target, velocity, look.scale(type.force));
		hitstop(player.level().getServer(), PUNCH_HITSTOP_MILLIS);
		ServerPlayNetworking.send(player, new PunchResultPayload(PunchResultPayload.Result.ENEMY, hitPoint, gore, target.getId()));
		return true;
	}

	/** Minecraft's bosses, as ULTRAKILL's (EnemyIdentifier.isBoss). */
	private static boolean boss(LivingEntity target) {
		return target instanceof WitherBoss || target instanceof EnderDragon;
	}

	/**
	 * Enemy.KnockBack, in place of Minecraft's knockback from the hit: the enemy stops falling, rises a little if
	 * it's in the air, and is sent along the force. Bosses stand their ground, as ULTRAKILL's heaviest enemies do.
	 *
	 * @param velocity the enemy's velocity before the hit
	 * @param force units per second
	 */
	private static void knockBack(LivingEntity target, Vec3 velocity, Vec3 force) {
		if (boss(target)) {
			target.setDeltaMovement(velocity);
			target.needsSync = true;
			return;
		}
		double y = Math.max(velocity.y, 0);
		if (!target.onGround()) {
			y += 1 * S / 20;
		}
		target.setDeltaMovement(new Vec3(velocity.x, y, velocity.z).add(force.scale(S / 20)));
		target.needsSync = true;
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
		hitstop(player.level().getServer(), PARRY_HITSTOP_MILLIS);
		ServerPlayNetworking.send(player, new PunchResultPayload(PunchResultPayload.Result.PARRY, projectile.position(), Gore.BODY, -1));
	}

	/** TimeController's HitStop and TrueStop: the world stops for a moment. */
	private static void hitstop(MinecraftServer server, long millis) {
		ServerTickRateManager rates = server.tickRateManager();
		if (!rates.isFrozen()) {
			rates.setFrozen(true);
			hitstopFroze = true;
		}
		if (hitstopFroze) {
			hitstopEnd = Math.max(hitstopEnd, Util.getMillis() + millis);
		}
	}
}
