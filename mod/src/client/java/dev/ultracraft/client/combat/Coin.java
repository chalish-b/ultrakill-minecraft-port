package dev.ultracraft.client.combat;

import dev.ultracraft.PlayerShape;
import dev.ultracraft.client.UltraConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * A marksman coin (Coin): thrown, hittable once it's been in the air for a moment, and when shot it sends the
 * shot on to the next coin or to an enemy's head.
 */
final class Coin {
	/** The coin's trigger sphere: 1 unit. */
	static final double HIT_RADIUS = 1 * PlayerShape.BLOCKS_PER_UNIT;
	private static final double GRAVITY = 40;
	private static final double LIFETIME = 5;
	/** Coins can be hit after StartCheckingSpeed, 0.1 s after the throw. */
	private static final double ARM_TIME = 0.1;
	/** A shot coin passes the shot on after this long (ReflectRevolver). */
	static final double REFLECT_DELAY = 0.1;
	/** How long a point of the coin's trail lasts (its TrailRenderer's time). */
	static final double TRAIL_TIME = 0.5;
	/** The trail adds a point once the coin is this far from the last one. */
	private static final double TRAIL_MIN_DISTANCE = 0.1 * PlayerShape.BLOCKS_PER_UNIT;

	Vec3 position;
	/** Units per second. */
	private Vec3 velocity;
	private double age;
	private double timeToDelete = 1;
	boolean shot;
	double reflectTimer;
	float power = 2;
	/** The enemies this chain of coins has hit already, which it won't target again. */
	Set<Integer> chainHits = new HashSet<>();
	/** The beam that hit the coin, which it passes on (Coin's altBeam). */
	Effects.BeamStyle beam = Effects.BeamStyle.REVOLVER;
	/** The coin keeps the camera's rotation from when it was thrown. */
	final float yaw;
	final float pitch;
	/** Trail points, newest first. */
	final Deque<TrailPoint> trail = new ArrayDeque<>();
	boolean removed;

	Coin(Vec3 position, Vec3 velocity, float yaw, float pitch) {
		this.position = position;
		this.velocity = velocity;
		this.yaw = yaw;
		this.pitch = pitch;
		this.trail.addFirst(new TrailPoint(position, 0));
	}

	record TrailPoint(Vec3 position, double time) {
	}

	double age() {
		return this.age;
	}

	boolean hittable() {
		return !this.shot && !this.removed && this.age >= ARM_TIME;
	}

	/** Shot by a beam (or another coin): passes it on after a moment. */
	void hit(float power, Set<Integer> chainHits, Effects.BeamStyle beam) {
		this.shot = true;
		this.power = power;
		this.chainHits = chainHits;
		this.beam = beam;
		this.reflectTimer = REFLECT_DELAY;
	}

	/** Moves the coin, and marks it removed once it expires or comes to rest. */
	void update(double dt, Level level, Entity thrower) {
		this.age += dt;
		if (this.age > LIFETIME) {
			this.removed = true;
			return;
		}
		updateTrail();
		if (this.shot) {
			return;
		}
		this.velocity = this.velocity.add(0, -GRAVITY * dt, 0);
		Vec3 next = this.position.add(this.velocity.scale(dt * PlayerShape.BLOCKS_PER_UNIT * UltraConfig.get().speedScale));
		BlockHitResult hit = level.clip(new ClipContext(this.position, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, thrower));
		if (hit.getType() != HitResult.Type.MISS) {
			this.position = hit.getLocation().add(hit.getDirection().getUnitVec3().scale(0.02));
			this.velocity = Vec3.ZERO;
		} else {
			this.position = next;
		}
		if (this.age >= ARM_TIME && this.velocity.length() < 1) {
			this.timeToDelete -= dt * 10;
			if (this.timeToDelete <= 0) {
				this.removed = true;
			}
		} else {
			this.timeToDelete = 1;
		}
	}

	private void updateTrail() {
		while (!this.trail.isEmpty() && this.age - this.trail.peekLast().time > TRAIL_TIME) {
			this.trail.removeLast();
		}
		if (this.trail.isEmpty() || this.position.distanceTo(this.trail.peekFirst().position) > TRAIL_MIN_DISTANCE) {
			this.trail.addFirst(new TrailPoint(this.position, this.age));
		}
	}
}
