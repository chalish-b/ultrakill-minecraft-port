package dev.ultracraft.client.combat;

import dev.ultracraft.PlayerShape;
import dev.ultracraft.client.UltraConfig;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * A core the Core Eject throws (Grenade, not a rocket): it tumbles along under gravity and explodes on the first
 * wall or enemy it touches. Shot with a revolver (or reached by a coin's shot), it makes a super explosion.
 */
final class Core extends Grenade {
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	/** Physics.gravity, units per second squared. */
	private static final double GRAVITY = 40;
	/** Its object is removed after this long (RemoveOnTime). */
	private static final double LIFETIME = 30;
	/** Its capsule's radius: 0.4 at the core's 0.5 scale. */
	static final double RADIUS = 0.2;
	/** The trigger sphere that shots, coins and explosions find it by: radius 0.5, at 5 and then 0.5 scale. */
	private static final double HIT_RADIUS = 1.25 * S;
	/** The Rigidbody's angular drag, and the physics settings' cap on how fast anything spins (radians a second). */
	private static final double ANGULAR_DRAG = 0.05;
	private static final float MAX_SPIN = 7;
	/** How long a point of its trail lasts (its TrailRenderer's time). */
	static final double TRAIL_TIME = 0.25;
	private static final double TRAIL_MIN_DISTANCE = 0.1;

	/** What ends a core. */
	interface Hits {
		/** It touched a wall or, if {@code enemy}, an enemy, and explodes. */
		void coreLanded(Core core, boolean enemy);
	}

	/** Units per second. */
	private Vec3 velocity;
	/** Its rotation in the world, as Minecraft draws it. */
	final Quaternionf rotation;
	/** Radians per second, in the world. */
	private final Vector3f spin;
	private double age;
	/** Trail points, newest first. */
	final Deque<Pellet.TrailPoint> trail = new ArrayDeque<>();

	Core(Vec3 position, Vec3 velocity, Quaternionf rotation, Vector3f spin) {
		super(position);
		this.velocity = velocity;
		this.rotation = rotation;
		this.spin = spin.length() > MAX_SPIN ? spin.normalize(MAX_SPIN) : spin;
		this.trail.addFirst(new Pellet.TrailPoint(position, 0));
	}

	double age() {
		return this.age;
	}

	@Override
	Vec3 hitCenter() {
		return this.position;
	}

	@Override
	double hitRadius() {
		return HIT_RADIUS;
	}

	/** A Knuckleblaster's blast or the like sets its velocity. */
	@Override
	void launch(Vec3 velocity) {
		this.velocity = velocity;
	}

	void update(double dt, Level level, LocalPlayer player, Hits hits) {
		this.age += dt;
		updateTrail();
		if (this.age > LIFETIME) {
			this.removed = true;
			return;
		}
		float angle = this.spin.length() * (float)dt;
		if (angle > 0) {
			this.rotation.premul(new Quaternionf().rotateAxis(angle, new Vector3f(this.spin).normalize()));
		}
		this.spin.mul((float)(1 / (1 + ANGULAR_DRAG * dt)));
		if (this.hooked) {
			return;
		}
		this.velocity = this.velocity.add(0, -GRAVITY * dt, 0);
		Vec3 next = this.position.add(this.velocity.scale(dt * S * UltraConfig.get().speedScale));
		LivingEntity enemy = firstEnemy(level, player, next);
		if (enemy != null) {
			this.removed = true;
			hits.coreLanded(this, true);
			return;
		}
		BlockHitResult wall = level.clip(new ClipContext(this.position, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		if (wall.getType() != HitResult.Type.MISS) {
			this.position = wall.getLocation();
			this.removed = true;
			hits.coreLanded(this, false);
			return;
		}
		this.position = next;
	}

	private LivingEntity firstEnemy(Level level, LocalPlayer player, Vec3 end) {
		double radius = RADIUS * S;
		LivingEntity first = null;
		double nearest = Double.MAX_VALUE;
		Vec3 at = end;
		for (Entity entity : level.getEntities(player, new AABB(this.position, end).inflate(radius), e -> e instanceof LivingEntity living && living.isAlive() && !e.isSpectator())) {
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
		while (!this.trail.isEmpty() && this.age - this.trail.peekLast().time() > TRAIL_TIME) {
			this.trail.removeLast();
		}
		if (this.trail.isEmpty() || this.position.distanceTo(this.trail.peekFirst().position()) > TRAIL_MIN_DISTANCE * S) {
			this.trail.addFirst(new Pellet.TrailPoint(this.position, this.age));
		}
	}
}
