package dev.ultracraft.client.combat;

import com.google.gson.JsonObject;
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
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * A drop of the Firestarter's gasoline (GasolineProjectile): it flies out and falls, trailing a dark streak, through
 * water too, until its trigger sphere touches an enemy, which soaks it up, or it meets a wall, which it stains.
 */
final class GasolineProjectile {
	static final String MODEL = "gasoline_projectile";
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	/** Time.fixedDeltaTime. */
	private static final double STEP = 0.008;
	/** Physics.gravity, units per second squared. */
	private static final double GRAVITY = 40;
	/** It's gone this long after it's shot if it meets nothing. */
	private static final double LIFETIME = 60;

	interface Hits {
		void gasolineEnemy(GasolineProjectile drop, LivingEntity enemy);

		void gasolineWall(GasolineProjectile drop, BlockHitResult wall);
	}

	/** Its prefab's GasolineProjectile script. */
	final JsonObject script;
	Vec3 position;
	/** Units per second. */
	private Vec3 velocity;
	private double age;
	private double accumulator;
	boolean removed;
	/** Trail points, newest first. */
	final Deque<Pellet.TrailPoint> trail = new ArrayDeque<>();

	/**
	 * @param velocity units per second
	 */
	GasolineProjectile(JsonObject script, Vec3 position, Vec3 velocity) {
		this.script = script;
		this.position = position;
		this.velocity = velocity;
		this.trail.addFirst(new Pellet.TrailPoint(position, 0));
	}

	void update(double dt, Level level, LocalPlayer player, Hits hits) {
		this.age += dt;
		if (this.age >= LIFETIME || this.position.y < level.getMinY() - 64) {
			this.removed = true;
			return;
		}
		this.accumulator += dt;
		while (this.accumulator >= STEP && !this.removed) {
			this.accumulator -= STEP;
			step(level, player, hits);
		}
		updateTrail();
	}

	/** A physics step: gravity, then on until its trigger touches an enemy or it meets a wall. */
	private void step(Level level, LocalPlayer player, Hits hits) {
		this.velocity = this.velocity.add(0, -GRAVITY * STEP, 0);
		Vec3 next = this.position.add(this.velocity.scale(STEP * S * UltraConfig.get().speedScale));
		BlockHitResult wall = level.clip(new ClipContext(this.position, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		boolean blocked = wall.getType() != HitResult.Type.MISS;
		LivingEntity enemy = firstEnemy(level, player, blocked ? wall.getLocation() : next);
		if (enemy != null) {
			this.removed = true;
			hits.gasolineEnemy(this, enemy);
			return;
		}
		if (blocked) {
			this.position = wall.getLocation();
			this.removed = true;
			hits.gasolineWall(this, wall);
			return;
		}
		this.position = next;
	}

	/** The first living enemy its trigger sphere touches on its way to {@code end}. */
	private @Nullable LivingEntity firstEnemy(Level level, LocalPlayer player, Vec3 end) {
		double radius = this.script.get("radius").getAsDouble() * S;
		AABB area = new AABB(this.position, end).inflate(radius);
		LivingEntity first = null;
		double nearest = Double.MAX_VALUE;
		for (Entity entity : level.getEntities(player, area, ThrownSaw::isEnemy)) {
			AABB box = entity.getBoundingBox().inflate(radius);
			Optional<Vec3> entry = box.contains(this.position) ? Optional.of(this.position) : box.clip(this.position, end);
			if (entry.isPresent() && entry.get().distanceToSqr(this.position) < nearest) {
				nearest = entry.get().distanceToSqr(this.position);
				first = (LivingEntity)entity;
			}
		}
		return first;
	}

	private void updateTrail() {
		JsonObject trail = this.script.getAsJsonObject("trail");
		while (!this.trail.isEmpty() && this.age - this.trail.peekLast().time() > trail.get("time").getAsDouble()) {
			this.trail.removeLast();
		}
		if (this.trail.isEmpty() || this.position.distanceTo(this.trail.peekFirst().position()) > trail.get("minVertexDistance").getAsDouble() * S) {
			this.trail.addFirst(new Pellet.TrailPoint(this.position, this.age));
		}
	}
}
