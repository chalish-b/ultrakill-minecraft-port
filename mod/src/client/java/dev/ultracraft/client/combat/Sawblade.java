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

/**
 * A chainsaw off its chain (Nail, as a sawblade): it flies straight on at its speed, bouncing off walls, and stops
 * on each enemy it meets to hit it four times. Hits and bounces wear it down until it breaks. A Feedbacker punch
 * after it has bounced sends it where the player aims, hitting twice as hard for a while. A magnet holding it
 * keeps it circling the magnet, and its bounces wear it less.
 */
final class Sawblade {
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	/** Time.fixedDeltaTime. */
	private static final double STEP = 0.008;
	/**
	 * RemoveTime, tripled for sawblades: this long after it's made or a magnet lets it go. MasterRemoveTime, and the
	 * cap on how far it can get from where it started (units).
	 */
	private static final double LIFETIME = 15;
	private static final double MASTER_LIFETIME = 60;
	private static final double FAR_AWAY = 1000;
	/** Hits on an enemy it stopped on come this often; the same enemy can't stop it again this soon. */
	private static final double MULTI_HIT_COOLDOWN = 0.15;
	private static final double SAME_ENEMY_COOLDOWN = 0.15;
	/** It hits enemies within this many units of it. */
	private static final double TOUCH_RADIUS = 0.5;
	/** A bounce wears it down this much (this much while a magnet holds it), and a further bounce in the same step this much. */
	private static final double BOUNCE_WEAR = 0.25;
	private static final double MAGNET_BOUNCE_WEAR = 0.1;
	/** A magnet turns it to fly this many degrees off the way to it, about the world's up. */
	private static final double MAGNET_ANGLE = 85;
	private static final double EXTRA_BOUNCE_WEAR = 0.125;
	/** Bits come off walls no more often than this. */
	private static final double GIB_INTERVAL = 0.25;
	/** A punch doubles its damage for this long. */
	private static final double PUNCHED_TIME = 3;
	private static final double TRAIL_MIN_DISTANCE = 0.1;
	private static final double BLADE_SCROLL = -10;

	interface Hits {
		/** Nail.DamageEnemy, twice as hard if punched. */
		void sawbladeEnemy(LivingEntity enemy, boolean punched);

		/** It bounced off a wall here, facing out of it, with a sound if it's the step's first, and maybe bits. */
		void sawRicochet(Vec3 at, Direction face, boolean sound, boolean gibs);

		/** It hit a block on a bounce, twice as hard if punched. */
		void sawbladeWall(BlockPos block, boolean punched);

		/** Nail.SawBreak. */
		void sawbladeBroke(Sawblade sawblade);
	}

	final JsonObject script;
	final UltraSounds.Loop spin;
	Vec3 position;
	/** Units per second. */
	private Vec3 velocity;
	private final Vec3 start;
	private boolean stopped;
	/** It has bounced, so a punch can send it on. */
	boolean punchable;
	private boolean punched;
	private double punchedTimer;
	private double hitAmount;
	private final int multiHitAmount;
	private int currentMultiHitAmount;
	private double multiHitCooldown;
	private @Nullable LivingEntity hitTarget;
	private @Nullable LivingEntity currentHitEnemy;
	private @Nullable LivingEntity previouslyHitEnemy;
	private double sameEnemyHitCooldown;
	private Vec3 originalVelocity = Vec3.ZERO;
	private double sinceLastGibs = 1;
	/** The magnets holding it, which side of the nearest it circles, and how far from it a punch sent it (units). */
	final List<Magnet> magnets = new ArrayList<>();
	private int magnetRotationDirection;
	private double punchDistance;
	private double removeAt = LIFETIME;
	private double accumulator;
	private double age;
	double bladeScroll;
	boolean removed;
	/** Trail points, newest first. */
	final Deque<Pellet.TrailPoint> trail = new ArrayDeque<>();

	Sawblade(JsonObject script, Vec3 position, Vec3 velocity, UltraSounds.Loop spin) {
		this.script = script;
		this.position = position;
		this.start = position;
		this.velocity = velocity;
		this.spin = spin;
		this.hitAmount = script.get("hitAmount").getAsDouble();
		this.multiHitAmount = script.get("multiHitAmount").getAsInt();
		this.trail.addFirst(new Pellet.TrailPoint(position, 0));
	}

	void update(double dt, Level level, LocalPlayer player, Hits hits) {
		this.age += dt;
		this.sinceLastGibs += dt;
		this.accumulator += dt;
		while (this.accumulator >= STEP && !this.removed) {
			this.accumulator -= STEP;
			step(level, player, hits);
		}
		if (this.removed) {
			return;
		}
		this.bladeScroll += BLADE_SCROLL * dt;
		if (this.sameEnemyHitCooldown > 0 && !this.stopped) {
			this.sameEnemyHitCooldown = Math.max(this.sameEnemyHitCooldown - dt, 0);
			if (this.sameEnemyHitCooldown <= 0) {
				this.currentHitEnemy = null;
			}
		}
		if (this.punched) {
			this.punchedTimer = Math.max(this.punchedTimer - dt, 0);
			if (this.punchedTimer <= 0) {
				this.punched = false;
			}
		}
		if (this.multiHitCooldown > 0) {
			this.multiHitCooldown = Math.max(this.multiHitCooldown - dt, 0);
		} else if (this.stopped) {
			boolean alive = this.currentHitEnemy != null && this.currentHitEnemy.isAlive();
			if (alive && this.currentMultiHitAmount > 0) {
				this.currentMultiHitAmount--;
				this.hitAmount--;
				hits.sawbladeEnemy(this.hitTarget, this.punched);
				alive = this.currentHitEnemy.isAlive();
			}
			if (!alive || this.currentMultiHitAmount <= 0) {
				this.stopped = false;
				this.velocity = this.originalVelocity;
				if (this.hitAmount <= 0) {
					breakApart(hits);
					return;
				}
			} else {
				this.multiHitCooldown = MULTI_HIT_COOLDOWN;
			}
		}
		updateTrail();
		if (this.age > this.removeAt || this.age > MASTER_LIFETIME || this.position.distanceTo(this.start) / S > FAR_AWAY) {
			this.removed = true;
		}
	}

	/** Nail.FixedUpdate for a sawblade. */
	private void step(Level level, LocalPlayer player, Hits hits) {
		if (this.stopped) {
			this.velocity = Vec3.ZERO;
			return;
		}
		if (!this.magnets.isEmpty()) {
			this.magnets.removeIf(magnet -> magnet.removed);
			if (this.magnets.isEmpty()) {
				return;
			}
			circle(targetMagnet());
		}
		AABB near = new AABB(this.position, this.position).inflate(TOUCH_RADIUS * S);
		for (Entity entity : level.getEntities(player, near, ThrownSaw::isEnemy)) {
			if (distance(entity.getBoundingBox(), this.position) <= TOUCH_RADIUS * S) {
				touchEnemy((LivingEntity)entity);
			}
		}
		double speed = this.velocity.length();
		if (speed == 0) {
			return;
		}
		Vec3 direction = this.velocity.scale(1 / speed);
		double distance = speed * STEP * S;
		OrientedBox box = box();
		double lead = Math.max(box.reach(this.position, direction), 0);
		BlockHitResult wall = level.clip(new ClipContext(this.position, this.position.add(direction.scale(lead + distance)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		double wallAt = wall.getType() == HitResult.Type.MISS ? Double.MAX_VALUE : Math.max(wall.getLocation().distanceTo(this.position) - lead, 0);
		AABB area = new AABB(box.center(), box.center()).inflate(lead + distance + 3 * S);
		List<Entity> touched = level.getEntities(player, area, ThrownSaw::isEnemy).stream()
			.filter(e -> box.moved(direction.scale(Math.min(distance, wallAt))).intersects(e.getBoundingBox()) || box.intersects(e.getBoundingBox()))
			.sorted(Comparator.comparingDouble(e -> e.distanceToSqr(this.position)))
			.toList();
		for (Entity entity : touched) {
			touchEnemy((LivingEntity)entity);
		}
		if (wall.getType() != HitResult.Type.MISS) {
			if (this.hitAmount <= 0) {
				breakApart(hits);
				return;
			}
			if (this.magnets.isEmpty()) {
				bounce(wall, speed, BOUNCE_WEAR, true, hits);
			} else {
				this.magnetRotationDirection *= -1;
				bounce(wall, speed, MAGNET_BOUNCE_WEAR, true, hits);
			}
			for (int i = 0; i < 3; i++) {
				Vec3 ahead = this.velocity.normalize();
				BlockHitResult next = level.clip(new ClipContext(this.position, this.position.add(ahead.scale(5 * S)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
				if (next.getType() == HitResult.Type.MISS) {
					break;
				}
				bounce(next, speed, EXTRA_BOUNCE_WEAR, false, hits);
			}
		}
		this.position = this.position.add(this.velocity.scale(STEP * S * UltraConfig.get().speedScale));
	}

	/** Off a wall at its full speed: bits of the wall, a spark, and it can be punched now. */
	private void bounce(BlockHitResult wall, double speed, double wear, boolean first, Hits hits) {
		this.position = wall.getLocation();
		this.velocity = ThrownSaw.reflect(this.velocity.normalize(), wall.getDirection()).scale(speed);
		boolean gibs = this.sinceLastGibs > GIB_INTERVAL;
		if (gibs) {
			this.sinceLastGibs = 0;
		}
		hits.sawRicochet(wall.getLocation(), wall.getDirection(), first, gibs);
		hits.sawbladeWall(wall.getBlockPos(), this.punched);
		this.punchable = true;
		this.sameEnemyHitCooldown = 0;
		this.currentHitEnemy = null;
		this.hitAmount -= wear;
	}

	/**
	 * Nail.FixedUpdate with magnets: it flies at its speed 85 degrees off the way to the magnet, round it. Punched,
	 * it flies on as punched until it's further from the magnet than it was.
	 */
	private void circle(Magnet magnet) {
		Vec3 center = magnet.center();
		if (this.punched) {
			if (center.distanceTo(this.position) / S <= this.punchDistance) {
				return;
			}
			this.punchDistance = 0;
		}
		Vec3 toward = center.subtract(this.position);
		if (toward.lengthSqr() == 0) {
			return;
		}
		this.velocity = turnY(toward.normalize(), MAGNET_ANGLE * this.magnetRotationDirection).scale(this.velocity.length());
	}

	/** Nail.GetTargetMagnet: the nearest magnet holding it, and which way round it goes, by which side of it the magnet is. */
	private Magnet targetMagnet() {
		Magnet nearest = null;
		double best = Double.MAX_VALUE;
		for (Magnet magnet : this.magnets) {
			Vec3 toward = magnet.center().subtract(this.position);
			double distance = toward.lengthSqr();
			if (distance < best) {
				best = distance;
				nearest = magnet;
				Vec3 side = new Vec3(this.velocity.z, this.velocity.y, -this.velocity.x).normalize();
				this.magnetRotationDirection = toward.dot(side) > 0 ? -1 : 1;
			}
		}
		return nearest;
	}

	/** Quaternion.Euler(0, degrees, 0) on {@code v}. */
	private static Vec3 turnY(Vec3 v, double degrees) {
		double radians = Math.toRadians(degrees);
		double cos = Math.cos(radians);
		double sin = Math.sin(radians);
		return new Vec3(v.x * cos + v.z * sin, v.y, -v.x * sin + v.z * cos);
	}

	/** Nail.MagnetCaught: it stays while a magnet holds it, and can be punched. */
	void caught(Magnet magnet) {
		if (this.magnets.contains(magnet)) {
			return;
		}
		this.removeAt = Double.POSITIVE_INFINITY;
		this.punchable = true;
		this.magnets.add(magnet);
	}

	/** Nail.MagnetRelease: let go by its last magnet, it's gone 15 seconds later. */
	void released(Magnet magnet) {
		this.magnets.remove(magnet);
		if (this.magnets.isEmpty()) {
			this.removeAt = this.age + LIFETIME;
		}
	}

	/** Magnet.Launch: the magnet breaking sends it straight at where the magnet was, at its speed. */
	void launched(Vec3 center) {
		Vec3 toward = center.subtract(this.position);
		if (toward.lengthSqr() > 0) {
			this.velocity = toward.normalize().scale(this.velocity.length());
		}
	}

	/** A chainsaw's sawblade (Nail.chainsaw): a magnet pulls it in to 20 units as it catches it. */
	boolean chainsaw() {
		return this.script.get("chainsaw").getAsInt() != 0;
	}

	double mass() {
		return this.script.get("mass").getAsDouble();
	}

	private void touchEnemy(LivingEntity enemy) {
		if (this.stopped || !enemy.isAlive()) {
			return;
		}
		if (this.sameEnemyHitCooldown > 0 && enemy == this.currentHitEnemy) {
			return;
		}
		this.stopped = true;
		this.currentMultiHitAmount = this.multiHitAmount;
		this.hitTarget = enemy;
		this.currentHitEnemy = enemy;
		if (this.previouslyHitEnemy != enemy) {
			if (this.previouslyHitEnemy != null) {
				this.punched = false;
			}
			this.previouslyHitEnemy = enemy;
		}
		this.originalVelocity = this.velocity;
		this.sameEnemyHitCooldown = SAME_ENEMY_COOLDOWN;
	}

	private void breakApart(Hits hits) {
		this.removed = true;
		hits.sawbladeBroke(this);
	}

	/** A Feedbacker punch: on towards {@code target} at the speed it had, hitting twice as hard for a while. */
	void punched(Vec3 target) {
		Vec3 toward = target.subtract(this.position).normalize();
		if (this.stopped) {
			this.stopped = false;
			this.velocity = toward.scale(this.originalVelocity.length());
		} else {
			this.velocity = toward.scale(this.velocity.length());
		}
		this.punched = true;
		this.punchedTimer = PUNCHED_TIME;
		if (!this.magnets.isEmpty()) {
			this.punchDistance = targetMagnet().center().distanceTo(this.position) / S;
		}
	}

	/** Its BoxCollider. It faces back the way it's going (Transform.LookAt behind it). */
	OrientedBox box() {
		return OrientedBox.of(this.position, forward(), this.script.getAsJsonObject("box"), 0.1 * S);
	}

	Vec3 forward() {
		Vec3 moving = this.stopped ? this.originalVelocity : this.velocity;
		return moving.lengthSqr() > 0 ? moving.normalize().scale(-1) : new Vec3(0, 0, 1);
	}

	double age() {
		return this.age;
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

	private static double distance(AABB box, Vec3 point) {
		double x = Math.clamp(point.x, box.minX, box.maxX) - point.x;
		double y = Math.clamp(point.y, box.minY, box.maxY) - point.y;
		double z = Math.clamp(point.z, box.minZ, box.maxZ) - point.z;
		return Math.sqrt(x * x + y * y + z * z);
	}
}
