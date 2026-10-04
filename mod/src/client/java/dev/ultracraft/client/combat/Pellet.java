package dev.ultracraft.client.combat;

import dev.ultracraft.PlayerShape;
import dev.ultracraft.client.UltraConfig;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
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
import java.util.Deque;
import java.util.Optional;

/**
 * A shotgun pellet ("Shotgun Projectile"): a small yellow ball flying straight on at its speed, trailing a short
 * streak. It stops at the first living enemy or wall it touches, and passes through the dying. Punched right
 * after the shot, it can be boosted: it turns orange, grows its hitbox, flies twice as fast at what the player
 * is aiming at, and explodes where it lands.
 */
final class Pellet {
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	/** Units per second, give or take its randomizer. */
	static final double SPEED = 75;
	static final double SPEED_RANDOMIZER = 10;
	/** Its trigger sphere: radius 0.5 at the projectile's 0.2 scale. */
	private static final double RADIUS = 0.1;
	/** Its object is removed after this long (RemoveOnTime). */
	private static final double LIFETIME = 5;
	/** How long a point of its trail lasts (its TrailRenderer's time). */
	static final double TRAIL_TIME = 0.1;
	/** The trail adds a point once the pellet is this far from the last one. */
	private static final double TRAIL_MIN_DISTANCE = 0.1;

	/** What the pellet reached; it's gone afterwards. */
	interface Hits {
		void enemy(Pellet pellet, LivingEntity enemy);

		void wall(Pellet pellet, BlockPos block);
	}

	Vec3 position;
	/** Length 1. */
	private Vec3 direction;
	/** Units per second. */
	private double speed;
	private double radius = RADIUS;
	boolean boosted;
	private double age;
	boolean removed;
	/** Trail points, newest first. */
	final Deque<TrailPoint> trail = new ArrayDeque<>();

	record TrailPoint(Vec3 position, double time) {
	}

	Pellet(Vec3 position, Vec3 direction, double speed) {
		this.position = position;
		this.direction = direction;
		this.speed = speed;
		this.trail.addFirst(new TrailPoint(position, 0));
	}

	double age() {
		return this.age;
	}

	/** Which way it flies, length 1. */
	Vec3 direction() {
		return this.direction;
	}

	/** Its hitbox's radius, in blocks. */
	double radius() {
		return this.radius * S;
	}

	/**
	 * Punch.ParryProjectile on the player's own pellet: it turns orange, its hitbox grows fourfold, and it flies
	 * at {@code target} at twice its speed, at least 100 units a second.
	 */
	void boost(Vec3 target) {
		this.boosted = true;
		this.radius *= 4;
		this.speed = Math.max(this.speed * 2, 100);
		Vec3 toTarget = target.subtract(this.position);
		if (toTarget.lengthSqr() > 0) {
			this.direction = toTarget.normalize();
		}
	}

	void update(double dt, Level level, LocalPlayer player, Hits hits) {
		this.age += dt;
		updateTrail();
		if (this.age > LIFETIME) {
			this.removed = true;
			return;
		}
		Vec3 next = this.position.add(this.direction.scale(this.speed * dt * S * UltraConfig.get().speedScale));
		BlockHitResult wall = level.clip(new ClipContext(this.position, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		Vec3 end = wall.getType() == HitResult.Type.MISS ? next : wall.getLocation();
		LivingEntity enemy = firstEnemy(level, player, end);
		if (enemy != null) {
			this.removed = true;
			hits.enemy(this, enemy);
		} else if (wall.getType() != HitResult.Type.MISS) {
			this.position = end;
			this.removed = true;
			hits.wall(this, wall.getBlockPos());
		} else {
			this.position = next;
		}
	}

	/** The first living enemy the pellet touches on its way to {@code end}; it's moved to where it touches. */
	private @Nullable LivingEntity firstEnemy(Level level, LocalPlayer player, Vec3 end) {
		double radius = radius();
		AABB area = new AABB(this.position, end).inflate(radius);
		LivingEntity first = null;
		double nearest = Double.MAX_VALUE;
		Vec3 at = end;
		for (Entity entity : level.getEntities(player, area, e -> e instanceof LivingEntity living && living.isAlive() && !e.isSpectator())) {
			AABB box = entity.getBoundingBox().inflate(radius);
			Optional<Vec3> entry = box.contains(this.position) ? Optional.of(this.position) : box.clip(this.position, end);
			if (entry.isPresent() && entry.get().distanceToSqr(this.position) < nearest) {
				nearest = entry.get().distanceToSqr(this.position);
				first = (LivingEntity)entity;
				at = entry.get();
			}
		}
		if (first != null) {
			this.position = at;
		}
		return first;
	}

	private void updateTrail() {
		while (!this.trail.isEmpty() && this.age - this.trail.peekLast().time > TRAIL_TIME) {
			this.trail.removeLast();
		}
		if (this.trail.isEmpty() || this.position.distanceTo(this.trail.peekFirst().position) > TRAIL_MIN_DISTANCE * S) {
			this.trail.addFirst(new TrailPoint(this.position, this.age));
		}
	}
}
