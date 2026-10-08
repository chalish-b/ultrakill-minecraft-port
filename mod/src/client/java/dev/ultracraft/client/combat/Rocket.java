package dev.ultracraft.client.combat;

import dev.ultracraft.PlayerShape;
import dev.ultracraft.client.UltraConfig;
import dev.ultracraft.client.movement.UltraSounds;
import dev.ultracraft.combat.WhiplashTargets;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * A rocket (Grenade, a rocket): it flies straight on and explodes on the first wall or enemy it touches. A wall
 * gets a harmless explosion, which only pushes; an enemy an explosion, and an enemy caught in the air a super
 * explosion. While the Freezeframe holds rockets frozen it hangs where it is, and after a second frozen it levels
 * up: from then on every enemy it hits gets a super explosion. A magnet stuck in a living enemy turns it toward
 * itself.
 */
final class Rocket extends Grenade {
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	/** Grenade.rocketSpeed: units per second. */
	private static final double SPEED = 100;
	/** Its object is removed after this long (RemoveOnTime). */
	private static final double LIFETIME = 30;
	/** The prefab root's scale, which its colliders are sized at. */
	private static final double SCALE = 0.35;
	/** Its CapsuleCollider: radius 1, reaching 6 ahead of its root. */
	private static final double RADIUS = 1 * SCALE;
	private static final double LENGTH = 6 * SCALE;
	/** Its trigger sphere, which shots, coins and the Whiplash find it by: radius 5, its middle 3 ahead. */
	private static final double HIT_RADIUS = 5 * SCALE;
	private static final double HIT_AHEAD = 3 * SCALE;
	/** Frozen this long, it levels up. */
	private static final double LEVEL_UP_TIME = 1;
	/** Unfrozen, a magnet holding it turns it toward the magnet by this many degrees a second. */
	private static final double MAGNET_TURN = 180;
	/** An enemy at most this old counts as in the air (EnemyIdentifier.timeSinceSpawned). */
	private static final double NEW_ENEMY_TIME = 0.15;
	/** Its hum's pitch (its AudioSource's), until its level-up sets it to 1. */
	private static final float HUM_PITCH = 0.5F;
	/** How long a point of its trail lasts, and how far apart they're laid (its TrailRenderer's). */
	static final double TRAIL_TIME = 0.15;
	private static final double TRAIL_MIN_DISTANCE = 0.1;

	/** What becomes of a rocket. */
	interface Hits {
		/**
		 * It touched a wall, or {@code enemy}, and explodes: a super explosion if {@code superExplosion}.
		 */
		void rocketHit(Rocket rocket, @Nullable LivingEntity enemy, boolean superExplosion);

		/** It levelled up: its LevelUpEffect comes on. */
		void rocketLevelledUp(Rocket rocket);

		/** The magnets out. */
		List<Magnet> magnets();
	}

	/** Which way it points. */
	Vec3 forward;
	/** Whether the Freezeframe holds it frozen (WeaponCharges.rocketFrozen), as of its last update. */
	boolean frozen;
	boolean levelledUp;
	private double timeFrozen;
	private double age;
	/** Trail points, newest first. */
	final Deque<Pellet.TrailPoint> trail = new ArrayDeque<>();
	/** Its LevelUpEffect's particle systems, which go with it, and how far ahead of its root they are (blocks). */
	private final List<Particles> levelUpParticles = new ArrayList<>();
	private double levelUpAhead;
	private final UltraSounds.Loop hum;

	Rocket(Vec3 position, Vec3 forward, UltraSounds sounds) {
		super(position);
		this.forward = forward;
		this.hum = sounds.loop();
		this.trail.addFirst(new Pellet.TrailPoint(position, 0));
	}

	double age() {
		return this.age;
	}

	@Override
	Vec3 hitCenter() {
		return this.position.add(this.forward.scale(HIT_AHEAD * S));
	}

	@Override
	double hitRadius() {
		return HIT_RADIUS * S;
	}

	/** Whatever sets its velocity, it flies on along its forward at its own speed. */
	@Override
	void launch(Vec3 velocity) {
	}

	/**
	 * Grenade.FixedUpdate: frozen, it stays put (counting toward its level-up) and only turns to face a magnet;
	 * otherwise it turns toward a magnet and flies on, until its capsule meets a wall or an enemy.
	 *
	 * @param frozen whether the Freezeframe holds rockets frozen
	 */
	void update(double dt, Level level, LocalPlayer player, boolean frozen, Hits hits) {
		this.age += dt;
		if (this.age > LIFETIME) {
			this.removed = true;
			return;
		}
		this.frozen = frozen;
		Magnet magnet = holdingMagnet(hits.magnets());
		if (frozen) {
			this.timeFrozen += dt;
			if (this.timeFrozen >= LEVEL_UP_TIME && !this.levelledUp) {
				this.levelledUp = true;
				hits.rocketLevelledUp(this);
			}
			if (magnet != null) {
				this.forward = magnet.center().subtract(this.position).normalize();
			}
		} else if (magnet != null) {
			turnToward(magnet.center(), MAGNET_TURN * dt);
		}
		this.hum.update("rocket_fly", 1, this.levelledUp ? 1 : HUM_PITCH, hitCenter());
		for (Particles system : this.levelUpParticles) {
			system.moveTo(this.position.add(this.forward.scale(this.levelUpAhead)));
			system.turnTo(Effects.facing(this.forward));
		}
		updateTrail();
		if (this.hooked) {
			return;
		}
		Vec3 next = frozen ? this.position : this.position.add(this.forward.scale(SPEED * dt * S * UltraConfig.get().speedScale));
		if (!touch(level, player, next, hits)) {
			this.position = next;
		}
	}

	/**
	 * Grenade.Collision: the first wall or enemy its capsule meets on its way to {@code next} stops it there, and it
	 * explodes. A levelled-up rocket, or one meeting a walking enemy that's in the air or has only just appeared,
	 * makes a super explosion.
	 *
	 * @return whether it touched something
	 */
	private boolean touch(Level level, LocalPlayer player, Vec3 next, Hits hits) {
		Vec3 tail = this.position;
		Vec3 nose = next.add(this.forward.scale(LENGTH * S));
		double radius = RADIUS * S;
		BlockHitResult wall = level.clip(new ClipContext(tail, nose, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		double nearest = wall.getType() != HitResult.Type.MISS ? wall.getLocation().distanceTo(tail) : Double.MAX_VALUE;
		LivingEntity enemy = null;
		for (Entity entity : level.getEntities(player, new AABB(tail, nose).inflate(radius), e -> e instanceof LivingEntity living && living.isAlive() && !e.isSpectator())) {
			AABB box = entity.getBoundingBox().inflate(radius);
			Optional<Vec3> entry = box.contains(tail) ? Optional.of(tail) : box.clip(tail, nose);
			if (entry.isPresent() && entry.get().distanceTo(tail) < nearest) {
				nearest = entry.get().distanceTo(tail);
				enemy = (LivingEntity)entity;
			}
		}
		if (nearest == Double.MAX_VALUE) {
			return false;
		}
		// Its root stops where its capsule's nose meets what it touched.
		this.position = tail.add(this.forward.scale(Math.max(nearest - LENGTH * S, 0)));
		this.removed = true;
		boolean superExplosion = enemy != null && (this.levelledUp || airborne(enemy));
		hits.rocketHit(this, enemy, superExplosion);
		return true;
	}

	/** A walking enemy off the ground, or one that has only just appeared. */
	private static boolean airborne(LivingEntity enemy) {
		return !WhiplashTargets.flying(enemy) && (!enemy.onGround() || enemy.tickCount * 0.05 <= NEW_ENEMY_TIME);
	}

	/** The latest magnet stuck in a living enemy whose field it's in (Magnet.HandleEnter catches rockets only then). */
	private @Nullable Magnet holdingMagnet(List<Magnet> magnets) {
		Magnet holding = null;
		for (Magnet magnet : magnets) {
			LivingEntity enemy = magnet.onEnemy();
			if (!magnet.removed && enemy != null && enemy.isAlive() && hitCenter().distanceTo(magnet.center()) <= magnet.reach() + hitRadius()) {
				holding = magnet;
			}
		}
		return holding;
	}

	/** Quaternion.RotateTowards: its forward turns toward {@code target} by at most {@code degrees}. */
	private void turnToward(Vec3 target, double degrees) {
		Vec3 to = target.subtract(this.position);
		if (to.lengthSqr() == 0) {
			return;
		}
		to = to.normalize();
		double angle = Math.acos(Math.clamp(this.forward.dot(to), -1, 1));
		double step = Math.toRadians(degrees);
		if (angle <= step) {
			this.forward = to;
			return;
		}
		Vec3 axis = this.forward.cross(to);
		if (axis.lengthSqr() < 1.0E-12) {
			axis = Math.abs(this.forward.y) < 0.99 ? this.forward.cross(new Vec3(0, 1, 0)) : this.forward.cross(new Vec3(1, 0, 0));
		}
		axis = axis.normalize();
		// Rodrigues' rotation of the forward about the axis by the step.
		double cos = Math.cos(step);
		double sin = Math.sin(step);
		this.forward = this.forward.scale(cos).add(axis.cross(this.forward).scale(sin)).add(axis.scale(axis.dot(this.forward) * (1 - cos))).normalize();
	}

	private void updateTrail() {
		while (!this.trail.isEmpty() && this.age - this.trail.peekLast().time() > TRAIL_TIME) {
			this.trail.removeLast();
		}
		if (this.trail.isEmpty() || this.position.distanceTo(this.trail.peekFirst().position()) > TRAIL_MIN_DISTANCE * S) {
			this.trail.addFirst(new Pellet.TrailPoint(this.position, this.age));
		}
	}

	/** Its LevelUpEffect's particle systems, {@code ahead} blocks ahead of its root, which follow it from now on. */
	void attachLevelUp(List<Particles> systems, double ahead) {
		this.levelUpParticles.addAll(systems);
		this.levelUpAhead = ahead;
	}

	/** It's gone: its hum stops and its level-up particles go with it. */
	void discard() {
		this.removed = true;
		this.hum.stop();
		this.levelUpParticles.forEach(Particles::destroy);
		this.levelUpParticles.clear();
	}
}
