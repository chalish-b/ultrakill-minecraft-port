package dev.ultracraft.client.combat;

import dev.ultracraft.PlayerShape;
import dev.ultracraft.client.UltraConfig;
import dev.ultracraft.client.movement.UltraMovement;
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
 *
 * <p>Once it's been frozen or caught by the Whiplash, a player falling onto it gets on and rides it: it carries them
 * on top, slower, steered by the movement keys, and pulls ever more downward the longer the ride goes on, and the
 * more rockets the player has ridden without landing. Jumping or sliding gets off; the player hitting a wall ends
 * the ride with a harmless explosion there, and an enemy the ridden rocket hits gets a super explosion.
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
	/** Ridden, it flies at this share of its speed, and its downpull draws it toward falling at this speed. */
	private static final double RIDE_SPEED_SHARE = 0.65;
	private static final double RIDE_FALL_SPEED = 100;
	/**
	 * Its downpull starts here and grows by this much a second, up to twice as fast while it climbs; every ride
	 * after this many since the player last landed starts with this much more.
	 */
	private static final double DOWNPULL_START = -0.5;
	private static final double DOWNPULL_RATE = 1 / 4.5;
	private static final int FREE_RIDES = 3;
	private static final double DOWNPULL_PER_RIDE = 0.25;
	/**
	 * The player gets on with their ground check within this many units of a point a unit ahead of its root, and is
	 * out of range again this far off.
	 */
	private static final double MOUNT_AHEAD = 1;
	private static final double MOUNT_REACH = 2.25;
	private static final double MOUNT_LEAVE = 3;
	/** Ridden, the movement keys turn it this many degrees a second. */
	private static final double STEER_SPEED = 165;
	/**
	 * The rider stands this high above it, a unit ahead of its root; with a ceiling within this much above, they sit
	 * level with it instead, below a ceiling this close.
	 */
	private static final double SEAT_HEIGHT = 2;
	private static final double SEAT_CEILING = 4;
	private static final double SEAT_LOW_CEILING = 2;
	/** The rider further than this (and a frame's flight) from it falls off. */
	private static final double RIDE_REACH = 5;
	/** Riding shakes the camera this much every frame. */
	private static final double RIDE_SHAKE = 0.1;
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

	/** Which way it points, and its own up. */
	Vec3 forward;
	private Vec3 up;
	/** Units per second. */
	private Vec3 velocity = Vec3.ZERO;
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
	private final UltraSounds sounds;
	private final UltraSounds.Loop hum;
	/** Grenade.rideable: it's been frozen or caught by the Whiplash, so the player can get on it. */
	boolean rideable;
	boolean playerRiding;
	/** The player was in range to get on and hasn't left it since (it starts that way, so a new rocket isn't got on at once). */
	private boolean playerInRidingRange = true;
	private boolean hasBeenRidden;
	private double downpull = DOWNPULL_START;

	Rocket(Vec3 position, Vec3 forward, Vec3 up, UltraSounds sounds) {
		super(position);
		this.forward = forward;
		this.up = up;
		this.sounds = sounds;
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

	/** Units per second. */
	Vec3 velocity() {
		return this.velocity;
	}

	/**
	 * Grenade.Update, FixedUpdate and LateUpdate. Frozen, it stays put (counting toward its level-up) and only turns
	 * to face a magnet; otherwise it turns toward a magnet and flies on, until its capsule meets a wall or an
	 * enemy. Ridden, it flies slower and pulls down, and carries the player; else a player falling onto it gets on.
	 *
	 * @param movement the player's ULTRAKILL movement, if it's on
	 * @param frozen whether the Freezeframe holds rockets frozen
	 */
	void update(double dt, Level level, LocalPlayer player, @Nullable UltraMovement movement, boolean frozen, Hits hits) {
		this.age += dt;
		if (this.age > LIFETIME) {
			this.removed = true;
			return;
		}
		this.frozen = frozen;
		if (this.playerRiding && (movement == null || !movement.ridingRocket())) {
			this.playerRiding = false;
		}
		if (this.playerRiding) {
			if (movement.jumpPressed()) {
				endRide(movement);
				movement.jump();
				movement.setPreSlide(this.velocity.length() / 24, 0.2);
			} else if (movement.slidePressed()) {
				endRide(movement);
			}
		}
		Magnet magnet = holdingMagnet(hits.magnets());
		if (frozen) {
			this.velocity = Vec3.ZERO;
			this.rideable = true;
			this.timeFrozen += dt;
			if (this.timeFrozen >= LEVEL_UP_TIME && !this.levelledUp) {
				this.levelledUp = true;
				hits.rocketLevelledUp(this);
			}
		} else if (this.playerRiding) {
			Vec3 cruise = this.forward.scale(SPEED * RIDE_SPEED_SHARE);
			this.velocity = cruise.lerp(new Vec3(0, -RIDE_FALL_SPEED, 0), Math.max(0, this.downpull));
			this.downpull += dt * DOWNPULL_RATE * Math.max(1, 1 + this.velocity.normalize().y);
		} else {
			this.velocity = this.forward.scale(SPEED);
		}
		if (this.playerRiding) {
			if (riderHits(level, player, movement, dt)) {
				hits.rocketHit(this, null, false);
				return;
			}
		} else if (movement != null) {
			mountCheck(movement);
		}
		if (frozen && magnet != null) {
			lookAt(magnet.center());
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
		Vec3 next = this.position.add(this.velocity.scale(dt * S * UltraConfig.get().speedScale));
		if (touch(level, player, next, hits)) {
			return;
		}
		this.position = next;
		if (this.playerRiding) {
			carry(level, player, movement, dt);
		}
	}

	/**
	 * Grenade.CheckPlayerCollision: whether the rider, carried along with it this frame, would run into a wall (or
	 * is in one already). Then the ride ends with the rider stopped there, and the rocket goes off where they are.
	 */
	private boolean riderHits(Level level, LocalPlayer player, UltraMovement movement, double dt) {
		AABB box = player.getBoundingBox();
		Vec3 allowed = Vec3.ZERO;
		boolean collided = !level.noCollision(player, box.deflate(1.0E-4));
		if (!collided) {
			Vec3 step = this.velocity.scale(dt * S * UltraConfig.get().speedScale);
			allowed = Entity.collideBoundingBox(player, step, box, level, List.of());
			collided = allowed.distanceToSqr(step) > 1.0E-10;
		}
		if (!collided) {
			return false;
		}
		endRide(movement);
		Vec3 stop = movement.rootPosition().add(allowed);
		movement.placeRider(stop);
		this.position = stop;
		this.removed = true;
		return true;
	}

	/**
	 * Grenade.FixedUpdate's mount: a rideable rocket the player's ground check comes within reach of while they're
	 * falling (or it's on the Whiplash) and in the air, they get on, once they've been out of range since it came.
	 */
	private void mountCheck(UltraMovement movement) {
		double distance = movement.groundCheckPoint().distanceTo(this.position.add(this.forward.scale(MOUNT_AHEAD * S))) / S;
		double falling = -movement.velocity().y;
		if (distance < MOUNT_REACH && (falling > 0 || this.hooked) && !movement.onGround() && this.rideable) {
			if (!movement.ridingRocket() && !this.playerInRidingRange) {
				startRide(movement);
			}
		} else if (this.playerInRidingRange && (distance > MOUNT_LEAVE || movement.onGround() || falling < 0 && !this.hooked)) {
			this.playerInRidingRange = false;
		}
	}

	/**
	 * Grenade.PlayerRideStart: the player gets on, with its sound. Every rocket ridden since the player last landed
	 * counts, and past the third, each starts with more downpull.
	 */
	void startRide(UltraMovement movement) {
		this.playerInRidingRange = true;
		this.playerRiding = true;
		movement.setRidingRocket(true);
		this.sounds.play("rocket_ride", 1, 1);
		if (!this.hasBeenRidden) {
			this.hasBeenRidden = true;
			int rides = movement.countRocketRide();
			if (rides > FREE_RIDES) {
				this.downpull += DOWNPULL_PER_RIDE * (rides - FREE_RIDES);
			}
		}
	}

	/** Grenade.PlayerRideEnd. */
	void endRide(UltraMovement movement) {
		this.playerRiding = false;
		movement.setRidingRocket(false);
	}

	/**
	 * Grenade.LateUpdate while ridden: a rider too far off falls off; otherwise the movement keys turn it (forward and
	 * back about its side, sideways about its up) and it puts the rider on top of it, the camera shaking.
	 */
	private void carry(Level level, LocalPlayer player, UltraMovement movement, double dt) {
		if (this.position.distanceTo(movement.rootPosition()) > (RIDE_REACH + this.velocity.length() * dt) * S) {
			endRide(movement);
			return;
		}
		Vec3 right = this.forward.cross(this.up).normalize();
		turn(right, -movement.moveY() * dt * STEER_SPEED);
		turn(this.up, -movement.moveX() * dt * STEER_SPEED);
		Vec3 ahead = this.position.add(this.forward.scale(S));
		Vec3 seat;
		if (distance(level, player, ahead, this.up, SEAT_CEILING) >= 0) {
			double ceiling = distance(level, player, ahead, new Vec3(0, 1, 0), SEAT_LOW_CEILING);
			seat = ceiling >= 0 ? ahead.add(0, -ceiling * S, 0) : ahead;
		} else {
			seat = this.position.add(this.up.scale(SEAT_HEIGHT * S)).add(this.forward.scale(S));
		}
		movement.placeRider(seat);
		movement.shake(RIDE_SHAKE);
	}

	/** How many units along {@code direction} from {@code from} a wall is, within {@code reach}, or -1. */
	private static double distance(Level level, LocalPlayer player, Vec3 from, Vec3 direction, double reach) {
		BlockHitResult hit = level.clip(new ClipContext(from, from.add(direction.scale(reach * S)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		return hit.getType() == HitResult.Type.MISS ? -1 : hit.getLocation().distanceTo(from) / S;
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
		boolean superExplosion = enemy != null && (this.levelledUp || this.playerRiding || airborne(enemy));
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

	/** Transform.LookAt: it faces {@code target}, its up as near the world's as it can be. */
	private void lookAt(Vec3 target) {
		Vec3 to = target.subtract(this.position);
		if (to.lengthSqr() == 0) {
			return;
		}
		this.forward = to.normalize();
		Vec3 up = new Vec3(0, 1, 0);
		Vec3 level = up.subtract(this.forward.scale(this.forward.dot(up)));
		if (level.lengthSqr() > 1.0E-12) {
			this.up = level.normalize();
		}
	}

	/** Quaternion.RotateTowards: it turns its forward toward {@code target} by at most {@code degrees}. */
	private void turnToward(Vec3 target, double degrees) {
		Vec3 to = target.subtract(this.position);
		if (to.lengthSqr() == 0) {
			return;
		}
		to = to.normalize();
		double angle = Math.toDegrees(Math.acos(Math.clamp(this.forward.dot(to), -1, 1)));
		Vec3 axis = this.forward.cross(to);
		if (axis.lengthSqr() < 1.0E-12) {
			axis = this.up;
		}
		turn(axis.normalize(), Math.min(angle, degrees));
	}

	/** It turns {@code degrees} about {@code axis} (a unit vector), anticlockwise looking down the axis. */
	private void turn(Vec3 axis, double degrees) {
		this.forward = rotate(this.forward, axis, degrees).normalize();
		this.up = rotate(this.up, axis, degrees);
		// Kept square to its forward.
		this.up = this.up.subtract(this.forward.scale(this.forward.dot(this.up))).normalize();
	}

	/** Rodrigues' rotation of {@code v} about the unit {@code axis}. */
	private static Vec3 rotate(Vec3 v, Vec3 axis, double degrees) {
		double radians = Math.toRadians(degrees);
		double cos = Math.cos(radians);
		double sin = Math.sin(radians);
		return v.scale(cos).add(axis.cross(v).scale(sin)).add(axis.scale(axis.dot(v) * (1 - cos)));
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
