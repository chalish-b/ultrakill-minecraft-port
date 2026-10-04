package dev.ultracraft.client.combat;

import com.google.gson.JsonObject;
import dev.ultracraft.PlayerShape;
import dev.ultracraft.client.UltraConfig;
import dev.ultracraft.client.movement.UltraSounds;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.OptionalDouble;

/**
 * The Sawed-On's thrown chainsaw (Chainsaw), on a chain from the player's camera. It flies out and the chain pulls
 * it back, harder the further it is; it bounces off walls at half speed, and stops on an enemy to hit it a few
 * times before carrying on. Back at the player it lingers a moment to be punched out again, then it's gone. If
 * the chain stays blocked by a wall, it snaps and the chainsaw flies on as a sawblade.
 */
final class ThrownSaw {
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	/** Time.fixedDeltaTime. */
	private static final double STEP = 0.008;
	/** The chain pulls it back to this speed (units a second). */
	private static final double PULL_SPEED = 100;
	/** After stopping on an enemy it carries on at no less than this speed. */
	private static final double RESUME_SPEED = 35;
	/** Punches raise the hits it gives an enemy up to this many. */
	private static final int MAX_HIT_AMOUNT = 3;
	/** The chainsaw's speed from a punch. */
	static final double PUNCH_SPEED = 105;
	/** Hits on an enemy it stopped on come this often, and the same enemy can't stop it again this soon after. */
	private static final double MULTI_HIT_COOLDOWN = 0.05;
	private static final double SAME_ENEMY_COOLDOWN = 0.25;
	/** It can't come back into the player for this long after being thrown or punched. */
	private static final double IGNORE_PLAYER = 0.66;
	/** Back at the player, it stays this long for a punch. */
	private static final double PLAYER_LINGER = 0.25;
	/** A chain blocked this long snaps. */
	private static final double BLOCKED_SNAP = 0.25;
	/** It looks ahead this many physics steps for what it's about to touch. */
	private static final int LOOKAHEAD_STEPS = 5;
	private static final double FAR_AWAY = 1000;
	private static final double TRAIL_MIN_DISTANCE = 0.1;
	/** The blade's texture runs this fast (its ScrollingTexture). */
	private static final double BLADE_SCROLL = -10;

	interface Hits {
		/** Chainsaw.DamageEnemy. */
		void sawEnemy(LivingEntity enemy);

		/** It bounced off a wall here, facing out of it; only the first bounce of a step makes a sound. */
		void sawRicochet(Vec3 at, Direction face, boolean sound);

		/** It hit a block on a bounce. */
		void sawWall(BlockPos block);

		/** Chainsaw.TurnIntoSawblade: the chain snapped. */
		void sawSnapped(ThrownSaw saw);
	}

	final JsonObject script;
	final UltraSounds.Loop spin;
	Vec3 position;
	/** Units per second. */
	private Vec3 velocity;
	/** Which way it faces: away from the player, or (while back at the player) out of the camera's back. */
	Vec3 forward = new Vec3(0, 0, 1);
	private boolean stopped;
	boolean inPlayer;
	/** A punch has found it; it waits for the punch instead of leaving the player. */
	boolean beingPunched;
	private int hitAmount;
	private int currentHitAmount;
	private @Nullable LivingEntity hitTarget;
	private @Nullable LivingEntity currentHitEnemy;
	private double multiHitCooldown;
	private double sameEnemyHitCooldown;
	private Vec3 originalVelocity = Vec3.ZERO;
	private double playerHitTimer;
	private double sinceIgnoringPlayer;
	private double raycastBlockedTimer;
	private double accumulator;
	private double age;
	double bladeScroll;
	boolean removed;
	/** Trail points, newest first. */
	final Deque<Pellet.TrailPoint> trail = new ArrayDeque<>();

	ThrownSaw(JsonObject script, Vec3 position, UltraSounds.Loop spin) {
		this.script = script;
		this.position = position;
		this.velocity = Vec3.ZERO;
		this.spin = spin;
		this.hitAmount = script.get("hitAmount").getAsInt();
		this.trail.addFirst(new Pellet.TrailPoint(position, 0));
	}

	/**
	 * Shotgun.ShootSaw: made, it checks for walls just ahead and an enemy right on top of it (which it stops on,
	 * before it's sent off), then it's thrown.
	 */
	void launch(Level level, LocalPlayer player, Vec3 velocity, Hits hits) {
		checkMultipleRicochets(level, player, hits);
		List<Entity> close = level.getEntities(player, new AABB(this.position, this.position).inflate(1.5 * S), ThrownSaw::isEnemy);
		if (!close.isEmpty()) {
			touchEnemy((LivingEntity)close.getFirst());
		}
		this.velocity = velocity;
	}

	/**
	 * @param anchor where the chain pulls it to: the camera, in blocks
	 * @param cameraForward the camera's facing, a unit vector
	 */
	void update(double dt, Level level, LocalPlayer player, Vec3 anchor, Vec3 cameraForward, Hits hits) {
		this.age += dt;
		this.accumulator += dt;
		while (this.accumulator >= STEP && !this.removed) {
			this.accumulator -= STEP;
			step(level, player, anchor, hits);
		}
		if (this.removed) {
			return;
		}
		this.sinceIgnoringPlayer += dt;
		this.bladeScroll += BLADE_SCROLL * dt;
		this.forward = this.inPlayer ? cameraForward.scale(-1) : away(anchor);
		if (this.sameEnemyHitCooldown > 0 && !this.stopped) {
			this.sameEnemyHitCooldown = Math.max(this.sameEnemyHitCooldown - dt, 0);
			if (this.sameEnemyHitCooldown <= 0) {
				this.currentHitEnemy = null;
			}
		}
		if (this.inPlayer) {
			this.position = anchor;
			this.playerHitTimer = Math.min(this.playerHitTimer + dt, PLAYER_LINGER);
			if (this.playerHitTimer >= PLAYER_LINGER && !this.beingPunched) {
				this.removed = true;
			}
		} else if (this.hitAmount > 1) {
			if (this.multiHitCooldown > 0) {
				this.multiHitCooldown = Math.max(this.multiHitCooldown - dt, 0);
			} else if (this.stopped) {
				boolean alive = this.hitTarget != null && this.hitTarget.isAlive();
				if (alive && this.currentHitAmount > 0) {
					this.currentHitAmount--;
					hits.sawEnemy(this.hitTarget);
					alive = this.hitTarget.isAlive();
				}
				if (!alive || this.currentHitAmount <= 0) {
					this.stopped = false;
					double speed = this.originalVelocity.length();
					this.velocity = speed > 0 ? this.originalVelocity.scale(Math.max(speed, RESUME_SPEED) / speed) : Vec3.ZERO;
				} else {
					this.multiHitCooldown = MULTI_HIT_COOLDOWN;
				}
			}
		}
		updateTrail();
		if (this.position.distanceTo(anchor) / S > FAR_AWAY) {
			this.removed = true;
		}
	}

	/** Chainsaw.FixedUpdate. */
	private void step(Level level, LocalPlayer player, Vec3 anchor, Hits hits) {
		if (this.stopped) {
			this.velocity = Vec3.ZERO;
			return;
		}
		Vec3 toAnchor = anchor.subtract(this.position);
		double distance = toAnchor.length() / S;
		Vec3 pull = distance > 0 ? toAnchor.normalize() : Vec3.ZERO;
		double speed = this.velocity.length();
		boolean retracting = speed > 0 && this.velocity.scale(1 / speed).dot(pull) >= 0.5;
		if (!retracting) {
			this.velocity = moveTowards(this.velocity, pull.scale(PULL_SPEED), STEP * distance * 10);
		} else {
			this.velocity = pull.scale(Math.min(PULL_SPEED, moveTowards(speed, PULL_SPEED, STEP * Math.max(10, distance) * 10)));
		}
		if (this.sinceIgnoringPlayer > IGNORE_PLAYER && !this.inPlayer && distance < 1) {
			touchPlayer();
			return;
		}
		BlockHitResult blocked = level.clip(new ClipContext(this.position, anchor, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		this.raycastBlockedTimer = blocked.getType() == HitResult.Type.MISS ? 0 : this.raycastBlockedTimer + STEP;
		if (this.raycastBlockedTimer >= BLOCKED_SNAP) {
			this.removed = true;
			hits.sawSnapped(this);
			return;
		}
		sweep(level, player, hits);
		this.position = this.position.add(this.velocity.scale(STEP * S * UltraConfig.get().speedScale));
	}

	/**
	 * Rigidbody.SweepTestAll with its box, a few steps ahead: enemies and the player it would touch first, up to
	 * the first wall, which it bounces off at half speed.
	 */
	private void sweep(Level level, LocalPlayer player, Hits hits) {
		double speed = this.velocity.length();
		if (speed == 0) {
			return;
		}
		Vec3 direction = this.velocity.scale(1 / speed);
		double distance = speed * LOOKAHEAD_STEPS * STEP * S;
		OrientedBox box = box();
		double lead = Math.max(box.reach(this.position, direction), 0);
		BlockHitResult wall = level.clip(new ClipContext(this.position, this.position.add(direction.scale(lead + distance)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		double wallAt = wall.getType() == HitResult.Type.MISS ? Double.MAX_VALUE : Math.max(wall.getLocation().distanceTo(this.position) - lead, 0);
		List<Touch> touches = new ArrayList<>();
		AABB area = new AABB(box.center(), box.center()).inflate(lead + distance + 3 * S);
		for (Entity entity : level.getEntities((Entity)null, area, e -> e == player || isEnemy(e))) {
			OptionalDouble at = sweptEntry(box, direction, distance, entity.getBoundingBox());
			if (at.isPresent() && at.getAsDouble() < wallAt) {
				touches.add(new Touch(entity, at.getAsDouble()));
			}
		}
		touches.sort(Comparator.comparingDouble(Touch::distance));
		for (Touch touch : touches) {
			if (touch.entity == player) {
				if (this.sinceIgnoringPlayer > IGNORE_PLAYER) {
					touchPlayer();
				}
			} else {
				touchEnemy((LivingEntity)touch.entity);
			}
		}
		if (wall.getType() != HitResult.Type.MISS && !this.inPlayer) {
			this.position = wall.getLocation();
			this.velocity = reflect(direction, wall.getDirection()).scale(speed / 2);
			hits.sawRicochet(wall.getLocation(), wall.getDirection(), true);
			hits.sawWall(wall.getBlockPos());
			this.sinceIgnoringPlayer = 1;
			checkMultipleRicochets(level, player, hits);
		}
	}

	private record Touch(Entity entity, double distance) {
	}

	/** How far the box can move along {@code direction} before it touches {@code target}, within {@code distance}. */
	private static OptionalDouble sweptEntry(OrientedBox box, Vec3 direction, double distance, AABB target) {
		int samples = Math.max(1, (int)Math.ceil(distance / (0.25 * S)));
		for (int i = 0; i <= samples; i++) {
			double along = distance * i / samples;
			if (box.moved(direction.scale(along)).intersects(target)) {
				return OptionalDouble.of(along);
			}
		}
		return OptionalDouble.empty();
	}

	/** Chainsaw.CheckMultipleRicochets: up to three walls straight ahead within 5 units, each halving its speed. */
	private void checkMultipleRicochets(Level level, LocalPlayer player, Hits hits) {
		for (int i = 0; i < 3; i++) {
			double speed = this.velocity.length();
			if (speed == 0) {
				return;
			}
			Vec3 direction = this.velocity.scale(1 / speed);
			BlockHitResult wall = level.clip(new ClipContext(this.position, this.position.add(direction.scale(5 * S)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
			if (wall.getType() == HitResult.Type.MISS) {
				return;
			}
			this.position = wall.getLocation();
			this.velocity = reflect(direction, wall.getDirection()).scale(speed / 2);
			hits.sawRicochet(wall.getLocation(), wall.getDirection(), i == 0);
			hits.sawWall(wall.getBlockPos());
		}
	}

	private void touchPlayer() {
		this.inPlayer = true;
		this.stopped = true;
		this.originalVelocity = this.velocity;
	}

	private void touchEnemy(LivingEntity enemy) {
		if (this.hitAmount <= 1 || this.stopped || !enemy.isAlive()) {
			return;
		}
		if (this.sameEnemyHitCooldown > 0 && enemy == this.currentHitEnemy) {
			return;
		}
		this.stopped = true;
		this.currentHitAmount = this.hitAmount;
		this.hitTarget = enemy;
		this.currentHitEnemy = enemy;
		this.originalVelocity = this.velocity;
		this.sameEnemyHitCooldown = SAME_ENEMY_COOLDOWN;
	}

	/** Chainsaw.GetPunched, and the punch sending it from just ahead of the camera towards {@code target}. */
	void punched(Vec3 eye, Vec3 look, Vec3 target) {
		this.beingPunched = false;
		this.inPlayer = false;
		this.stopped = false;
		this.playerHitTimer = 0;
		this.sinceIgnoringPlayer = 0;
		this.sameEnemyHitCooldown = 0;
		if (this.hitAmount < MAX_HIT_AMOUNT) {
			this.hitAmount++;
		}
		this.position = eye.add(look.scale(S));
		this.velocity = target.subtract(this.position).normalize().scale(PUNCH_SPEED);
	}

	/** Which way the chainsaw is going, for the sawblade it turns into. */
	Vec3 heading() {
		Vec3 moving = this.stopped ? this.originalVelocity : this.velocity;
		return moving.lengthSqr() > 0 ? moving.normalize() : this.forward;
	}

	/** Its BoxCollider, which punches and its sweeps find it by. */
	OrientedBox box() {
		return OrientedBox.of(this.position, this.forward, this.script.getAsJsonObject("box"), 0.1 * S);
	}

	/** The stopped audio: higher while it's stopped on an enemy, lower flying, lowest back at the player. */
	double spinPitch() {
		return this.inPlayer ? 0.5 : this.stopped ? 1.1 : 0.85;
	}

	double spinVolume() {
		return this.inPlayer || this.stopped ? 0.75 : 0.5;
	}

	double age() {
		return this.age;
	}

	private Vec3 away(Vec3 anchor) {
		Vec3 away = this.position.subtract(anchor);
		return away.lengthSqr() > 1.0E-10 ? away.normalize() : this.forward;
	}

	private void updateTrail() {
		double time = this.script.getAsJsonObject("trail").get("time").getAsDouble();
		while (!this.trail.isEmpty() && this.age - this.trail.peekLast().time() > time) {
			this.trail.removeLast();
		}
		if (this.trail.isEmpty() || this.position.distanceTo(this.trail.peekFirst().position()) > TRAIL_MIN_DISTANCE * S) {
			this.trail.addFirst(new Pellet.TrailPoint(this.position, this.age));
		}
	}

	static boolean isEnemy(Entity entity) {
		return entity instanceof LivingEntity living && living.isAlive() && !entity.isSpectator() && !(entity instanceof LocalPlayer);
	}

	static Vec3 reflect(Vec3 direction, Direction face) {
		Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
		return direction.subtract(normal.scale(2 * direction.dot(normal)));
	}

	private static Vec3 moveTowards(Vec3 from, Vec3 to, double maxDelta) {
		Vec3 toward = to.subtract(from);
		double length = toward.length();
		return length <= maxDelta || length == 0 ? to : from.add(toward.scale(maxDelta / length));
	}

	private static double moveTowards(double from, double to, double maxDelta) {
		return Math.abs(to - from) <= maxDelta ? to : from + Math.signum(to - from) * maxDelta;
	}
}
