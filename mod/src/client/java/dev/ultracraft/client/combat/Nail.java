package dev.ultracraft.client.combat;

import com.google.gson.JsonObject;
import dev.ultracraft.PlayerShape;
import dev.ultracraft.client.UltraConfig;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A nail (Nail): it flies out at 200 units a second and falls, trailing a streak, and sticks in the first enemy its
 * trigger sphere touches, taking it along. The first wall it meets stops it with a ricochet, and it's gone a second
 * later; one that hits nothing is gone after five. Magnets pull it about instead: it bounces off walls without
 * stopping there, and stays as long as one holds it. A magnet that goes off throws its nails out as a nail bomb.
 */
final class Nail {
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	/** Time.fixedDeltaTime. */
	static final double STEP = 0.008;
	/** Physics.gravity, units per second squared. */
	private static final double GRAVITY = 40;
	/** Nailgun.Shoot sends it off at this speed. */
	static final double SPEED = 200;
	/** Its RemoveTime: this long after it's shot (or a magnet lets it go), or after it hits a wall. */
	private static final double LIFETIME = 5;
	private static final double WALL_LIFETIME = 1;
	/** MasterRemoveTime, and SlowUpdate's distance from where it started. */
	private static final double MASTER_LIFETIME = 60;
	private static final double FAR_AWAY = 1000;
	/** Unity's default physics material: walls take this much of the push into them off the speed along them. */
	private static final double FRICTION = 0.6;
	/** Stuck in an enemy, it's pushed half a unit further in. */
	private static final double STICK_DEPTH = 0.5;
	private static final double TRAIL_MIN_DISTANCE = 0.1;

	interface Hits {
		/** It struck a living enemy. */
		void nailEnemy(Nail nail, LivingEntity enemy);

		/** It struck a wall, stopping there. */
		void nailWall(Nail nail, BlockPos block, Vec3 at);
	}

	/**
	 * The nails a magnet threw out as it went (NailBurstController): each enemy they reach takes one hit of every
	 * nail's damage times half their number, rounded down, and no more of theirs. It ends after five seconds.
	 */
	static final class Burst {
		static final double LIFETIME = 5;
		final int nails;
		final Set<Integer> damaged = new HashSet<>();
		private double age;

		Burst(int nails) {
			this.nails = nails;
		}

		boolean over() {
			return this.age >= LIFETIME;
		}

		void update(double dt) {
			this.age += dt;
		}
	}

	/** Its prefab's Nail script. */
	final JsonObject script;
	final String model;
	final float damage;
	final boolean heated;
	Vec3 position;
	/** Units per second. */
	Vec3 velocity;
	/** Which way it points: back along where it's flying, as Nail.Update turns it, until it hits something. */
	Vec3 forward;
	private final Vec3 start;
	/** It has struck an enemy or (with no magnet holding it) a wall. */
	private boolean hit;
	/** The magnets holding it. */
	final List<Magnet> magnets = new ArrayList<>();
	/** The nail bomb it's part of. */
	@Nullable Burst burst;
	/** Nail.Zap: the enemy it's in conducts; it shows its zap material, and comes out with sparks. */
	boolean zapped;
	/** The enemy it's stuck in, where in it (relative to its position, turned with its body) and how it points there. */
	@Nullable LivingEntity stuckIn;
	private Vec3 offset = Vec3.ZERO;
	private Vec3 localForward = Vec3.ZERO;
	private double age;
	private double removeAt = LIFETIME;
	private double accumulator;
	boolean removed;
	/** Trail points, newest first; stuck in an enemy, it has none (its TrailRenderer is turned off). */
	final Deque<Pellet.TrailPoint> trail = new ArrayDeque<>();

	/**
	 * @param velocity units per second
	 */
	Nail(String model, JsonObject script, Vec3 position, Vec3 velocity) {
		this.model = model;
		this.script = script;
		this.damage = script.get("damage").getAsFloat();
		this.heated = script.get("heated").getAsInt() != 0;
		this.position = position;
		this.start = position;
		this.velocity = velocity;
		this.forward = velocity.normalize().scale(-1);
		this.trail.addFirst(new Pellet.TrailPoint(position, 0));
	}

	/** Its trigger sphere's radius, in blocks: what catches enemies and magnets. */
	double triggerRadius() {
		return this.script.get("triggerRadius").getAsDouble() * S;
	}

	double mass() {
		return this.script.get("mass").getAsDouble();
	}

	void update(double dt, float partialTick, Level level, LocalPlayer player, Hits hits) {
		this.age += dt;
		if (this.age >= this.removeAt || this.age >= MASTER_LIFETIME || this.position.distanceTo(this.start) / S > FAR_AWAY) {
			remove();
			return;
		}
		if (this.stuckIn != null) {
			if (this.stuckIn.isRemoved()) {
				remove();
				return;
			}
			follow(partialTick);
			updateTrail();
			return;
		}
		this.accumulator += dt;
		while (this.accumulator >= STEP && !this.removed && this.stuckIn == null) {
			this.accumulator -= STEP;
			step(level, player, hits);
		}
		if (!this.hit && this.velocity.lengthSqr() > 0) {
			this.forward = this.velocity.normalize().scale(-1);
		}
		updateTrail();
	}

	/** The physics steps a nail fired in a physics step takes before the frame it's fired in is drawn. */
	void fly(int steps, Level level, LocalPlayer player, Hits hits) {
		for (int i = 0; i < steps && !this.removed && this.stuckIn == null; i++) {
			step(level, player, hits);
		}
		if (!this.hit && this.velocity.lengthSqr() > 0) {
			this.forward = this.velocity.normalize().scale(-1);
		}
		updateTrail();
	}

	/** A physics step: magnets' pull and gravity, then flying on until a wall stops it or its trigger meets an enemy. */
	private void step(Level level, LocalPlayer player, Hits hits) {
		for (Magnet magnet : this.magnets) {
			this.velocity = this.velocity.add(magnet.pull(this).scale(STEP));
		}
		this.velocity = this.velocity.add(0, -GRAVITY * STEP, 0);
		Vec3 next = this.position.add(this.velocity.scale(STEP * S * UltraConfig.get().speedScale));
		BlockHitResult wall = level.clip(new ClipContext(this.position, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		boolean blocked = wall.getType() != HitResult.Type.MISS;
		if (!this.hit) {
			LivingEntity enemy = firstEnemy(level, player, blocked ? wall.getLocation() : next);
			if (enemy != null) {
				stick(enemy);
				hits.nailEnemy(this, enemy);
				return;
			}
		}
		if (!blocked) {
			this.position = next;
			return;
		}
		Vec3 normal = wall.getDirection().getUnitVec3();
		this.position = wall.getLocation().add(normal.scale(1.0E-3));
		collide(normal);
		if (this.magnets.isEmpty() && !this.hit) {
			this.hit = true;
			this.removeAt = this.age + WALL_LIFETIME;
			hits.nailWall(this, wall.getBlockPos(), wall.getLocation());
		}
	}

	/** A collision with a wall of {@code normal}: it loses its speed into the wall, and friction takes some of the rest. */
	private void collide(Vec3 normal) {
		double into = this.velocity.dot(normal);
		if (into >= 0) {
			return;
		}
		Vec3 along = this.velocity.subtract(normal.scale(into));
		double speed = along.length();
		double friction = Math.min(speed, FRICTION * -into);
		this.velocity = speed > 0 ? along.scale((speed - friction) / speed) : Vec3.ZERO;
	}

	/** The first living enemy its trigger sphere touches on its way to {@code end}; it's moved to where it touches. */
	private @Nullable LivingEntity firstEnemy(Level level, LocalPlayer player, Vec3 end) {
		double radius = triggerRadius();
		AABB area = new AABB(this.position, end).inflate(radius);
		LivingEntity first = null;
		double nearest = Double.MAX_VALUE;
		Vec3 at = end;
		for (Entity entity : level.getEntities(player, area, ThrownSaw::isEnemy)) {
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

	/** Nail.HitEnemy: it stops in the enemy, half a unit deeper along its flight, and goes where the enemy goes. */
	private void stick(LivingEntity enemy) {
		this.hit = true;
		if (this.velocity.lengthSqr() > 0) {
			this.forward = this.velocity.normalize().scale(-1);
		}
		this.position = this.position.subtract(this.forward.scale(STICK_DEPTH * S));
		this.stuckIn = enemy;
		this.offset = rotateY(this.position.subtract(enemy.position()), enemy.yBodyRot);
		this.localForward = rotateY(this.forward, enemy.yBodyRot);
		this.velocity = Vec3.ZERO;
		this.removeAt = Double.POSITIVE_INFINITY;
		this.trail.clear();
		for (Magnet magnet : List.copyOf(this.magnets)) {
			magnet.drop(this);
		}
		this.magnets.clear();
	}

	private void follow(float partialTick) {
		LivingEntity enemy = this.stuckIn;
		float yaw = Mth.rotLerp(partialTick, enemy.yBodyRotO, enemy.yBodyRot);
		this.position = enemy.getPosition(partialTick).add(rotateY(this.offset, -yaw));
		this.forward = rotateY(this.localForward, -yaw);
	}

	/** {@code v} turned about the world's up by the inverse of a body yaw of {@code yaw} degrees. */
	private static Vec3 rotateY(Vec3 v, float yaw) {
		double radians = Math.toRadians(yaw);
		double cos = Math.cos(radians);
		double sin = Math.sin(radians);
		return new Vec3(v.x * cos + v.z * sin, v.y, -v.x * sin + v.z * cos);
	}

	/** Nail.MagnetCaught: it stays while the magnet holds it, and it's no longer part of a nail bomb. */
	void caught(Magnet magnet) {
		if (this.magnets.contains(magnet)) {
			return;
		}
		this.removeAt = Double.POSITIVE_INFINITY;
		this.magnets.add(magnet);
		this.burst = null;
	}

	/** Nail.MagnetRelease: let go by its last magnet, it's gone five seconds later. */
	void released(Magnet magnet) {
		this.magnets.remove(magnet);
		if (this.magnets.isEmpty()) {
			this.removeAt = this.age + LIFETIME;
		}
	}

	void remove() {
		if (this.removed) {
			return;
		}
		this.removed = true;
		for (Magnet magnet : List.copyOf(this.magnets)) {
			magnet.drop(this);
		}
		this.magnets.clear();
	}

	private void updateTrail() {
		double time = this.script.getAsJsonObject("trail").get("time").getAsDouble();
		while (!this.trail.isEmpty() && this.age - this.trail.peekLast().time() > time) {
			this.trail.removeLast();
		}
		if (this.stuckIn == null && (this.trail.isEmpty() || this.position.distanceTo(this.trail.peekFirst().position()) > TRAIL_MIN_DISTANCE * S)) {
			this.trail.addFirst(new Pellet.TrailPoint(this.position, this.age));
		}
	}
}
