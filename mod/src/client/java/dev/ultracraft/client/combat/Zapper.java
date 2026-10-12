package dev.ultracraft.client.combat;

import com.google.gson.JsonObject;
import dev.ultracraft.PlayerShape;
import dev.ultracraft.client.UltraConfig;
import dev.ultracraft.client.movement.UltraSounds;
import dev.ultracraft.combat.WhiplashTargets;
import net.minecraft.client.player.LocalPlayer;
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

import java.util.Optional;

/**
 * The JumpStart's zapper (Zapper): a plug on a cable from the gun, or from the player while the gun is put away.
 * Shot, it flies in an arc, held within 15 units of where the player shot it from, and breaks on the first wall it
 * touches; the first living enemy within a unit of it, it attaches to, closing its prongs. Attached, it charges for
 * five seconds, a pulse running down the cable ever faster, then zaps the enemy (at once if the enemy dies first).
 * While the cable is longer than 20 units, or a wall is in its way, it warns, and it snaps after a second (half a
 * second while blocked). Sawblades cutting the enemy it's attached to boost its charge and its zap's damage.
 */
final class Zapper {
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	/** Time.fixedDeltaTime. */
	private static final double STEP = 0.008;
	/** The player's gravity, units per second squared. */
	private static final double GRAVITY = 40;
	/** Nailgun.ShootZapper sends it off at this speed. */
	static final double SPEED = 100;
	/** It charges up to this, a second at a time, then zaps. */
	static final double FULL_CHARGE = 5;
	/** Until it attaches, its joint holds it this many units short of the cable's reach. */
	private static final double JOINT_SLACK = 5;
	/** Its cable reddens over the last this many units of its reach. */
	private static final double REDDENING = 10;
	/** About to break, its cable flashes black and white this often. */
	private static final double FLASH = 0.1;
	private static final float[] GRAY = {0.5F, 0.5F, 0.5F, 1};
	private static final float[] RED = {1, 0, 0, 1};
	private static final float[] BLACK = {0, 0, 0, 1};
	private static final float[] WHITE = {1, 1, 1, 1};

	interface Hits {
		/** Zapper.Zap: it zaps the enemy it's attached to, from where it is. */
		void zap(Zapper zapper, LivingEntity enemy);

		/** Zapper.Break: it breaks, its cable snapping if it was attached and hadn't zapped. */
		void zapperBroke(Zapper zapper, boolean snapped);
	}

	final JsonObject script;
	private final UltraSounds sounds;
	private final UltraSounds.Loop hum;
	private final UltraSounds.Loop pulse;
	private final UltraSounds.Loop tighten;
	private final UltraSounds.Loop warning;
	/** Blocks; the way it faces; units per second. */
	Vec3 position;
	Vec3 forward;
	private Vec3 velocity;
	/** Its joint's anchor on the player: where it was shot from, relative to the player's body. */
	private final Vec3 anchorOffset;
	/** Where its cable starts (Zapper.lineStartTransform). */
	Vec3 lineStart;
	/** The enemy it's attached to, and where it sits in it (relative to its position, turned with its body). */
	@Nullable LivingEntity enemy;
	private Vec3 offset = Vec3.ZERO;
	private Vec3 localForward = Vec3.ZERO;
	boolean attached;
	double charge;
	/** Its zap's damage. */
	private float damage;
	/** Zapper.breakTimer: how far through its warning it is, 0 to 1. */
	double breakTimer;
	/** The cable's length, in units, and whether a wall is in its way. */
	double distance;
	boolean raycastBlocked;
	private boolean warningOn;
	/** The pulse orb's lightning (its ParticleSystem), which runs while it's attached. */
	final @Nullable Particles orb;
	private double accumulator;
	boolean removed;

	/**
	 * @param orb its pulse orb's lightning
	 * @param root the player's body, which its joint holds it to
	 */
	Zapper(JsonObject script, @Nullable Particles orb, UltraSounds sounds, Vec3 position, Vec3 forward, Vec3 root) {
		this.script = script;
		this.orb = orb;
		this.sounds = sounds;
		this.hum = sounds.loop();
		this.pulse = sounds.loop();
		this.tighten = sounds.loop();
		this.warning = sounds.loop();
		this.position = position;
		this.forward = forward;
		this.velocity = forward.scale(SPEED);
		this.anchorOffset = position.subtract(root);
		this.lineStart = position;
		this.damage = script.get("damage").getAsFloat();
	}

	double maxDistance() {
		return this.script.get("maxDistance").getAsDouble();
	}

	float damage() {
		return this.damage;
	}

	/** Nail.HitEnemy on the enemy it's attached to (EnemyIdentifier.zapperer): its zap hits harder, and Zapper.ChargeBoost brings it sooner. */
	void boost(float damage, double charge) {
		this.damage += damage;
		this.charge += charge;
	}

	/**
	 * Zapper.FixedUpdate in each physics step, then Zapper.Update.
	 *
	 * @param lineStart where its cable starts now
	 * @param root the player's body
	 */
	void update(double dt, float partialTick, Level level, LocalPlayer player, Vec3 lineStart, Vec3 root, Hits hits) {
		this.lineStart = lineStart;
		if (this.attached && this.enemy != null) {
			follow(partialTick);
		}
		this.accumulator += dt;
		while (this.accumulator >= STEP && !this.removed) {
			this.accumulator -= STEP;
			fixedUpdate(level, player, root, hits);
		}
		if (this.removed) {
			return;
		}
		this.distance = this.position.distanceTo(this.lineStart) / S;
		if (this.warningOn) {
			float pitch = this.raycastBlocked ? 2 : 1;
			this.tighten.update("zapper_tighten", 1, pitch, this.position);
			this.warning.update("zapper_warning", 1, pitch);
		}
		if (this.attached && this.enemy != null) {
			this.charge = Nailgun.toward(this.charge, FULL_CHARGE, dt);
			this.hum.update("zapper_hum", 1, 1 + this.charge / FULL_CHARGE, this.position);
			this.pulse.update("zapper_pulse", 1, 1, pulsePosition());
			if (this.orb != null) {
				this.orb.moveTo(pulsePosition());
				this.orb.update(dt);
			}
			if (this.charge >= FULL_CHARGE || !this.enemy.isAlive()) {
				hits.zap(this, this.enemy);
				destroy(true, hits);
			}
		}
	}

	private void fixedUpdate(Level level, LocalPlayer player, Vec3 root, Hits hits) {
		if (!this.attached) {
			fly(level, player, root, hits);
			return;
		}
		this.raycastBlocked = !Hitscan.clear(level, player, this.position, this.lineStart);
		if (this.distance > maxDistance() || this.raycastBlocked) {
			this.warningOn = true;
			this.breakTimer = Nailgun.toward(this.breakTimer, 1, STEP * (this.raycastBlocked ? 2 : 1));
			if (this.breakTimer >= 1) {
				destroy(false, hits);
			}
			return;
		}
		if (this.breakTimer != 0) {
			this.warningOn = false;
			this.tighten.stop();
			this.warning.stop();
		}
		this.breakTimer = 0;
	}

	/**
	 * Its Rigidbody falling and its joint holding it to the player; touching a wall it breaks (OnCollisionEnter), and
	 * its trigger sphere attaches it to the first living enemy it meets (OnTriggerEnter).
	 */
	private void fly(Level level, LocalPlayer player, Vec3 root, Hits hits) {
		this.velocity = this.velocity.add(0, -GRAVITY * STEP, 0);
		Vec3 next = this.position.add(this.velocity.scale(STEP * S * UltraConfig.get().speedScale));
		BlockHitResult wall = level.clip(new ClipContext(this.position, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		boolean blocked = wall.getType() != HitResult.Type.MISS;
		Vec3 end = blocked ? wall.getLocation() : next;
		if (attachTo(level, player, end)) {
			return;
		}
		if (blocked) {
			this.position = wall.getLocation();
			destroy(false, hits);
			return;
		}
		this.position = next;
		Vec3 anchor = root.add(this.anchorOffset);
		Vec3 out = this.position.subtract(anchor);
		double limit = (maxDistance() - JOINT_SLACK) * S;
		if (out.length() > limit) {
			Vec3 direction = out.normalize();
			this.position = anchor.add(direction.scale(limit));
			double away = this.velocity.dot(direction);
			if (away > 0) {
				this.velocity = this.velocity.subtract(direction.scale(away));
			}
		}
	}

	/** Zapper.CheckAttach on the first living enemy its trigger sphere touches on its way to {@code end}. */
	private boolean attachTo(Level level, LocalPlayer player, Vec3 end) {
		double radius = this.script.get("triggerRadius").getAsDouble() * S;
		LivingEntity first = null;
		Vec3 contact = end;
		double nearest = Double.MAX_VALUE;
		for (Entity entity : level.getEntities(player, new AABB(this.position, end).inflate(radius), ThrownSaw::isEnemy)) {
			AABB box = entity.getBoundingBox().inflate(radius);
			Optional<Vec3> entry = box.contains(this.position) ? Optional.of(this.position) : box.clip(this.position, end);
			if (entry.isPresent() && entry.get().distanceToSqr(this.position) < nearest) {
				nearest = entry.get().distanceToSqr(this.position);
				first = (LivingEntity)entity;
				contact = entry.get();
			}
		}
		if (first == null) {
			return false;
		}
		attach(first, contact);
		return true;
	}

	/**
	 * Attached, it sits in the middle of the enemy, or on a big enemy where it touched it, facing in; it stops, its
	 * prongs close, its hum starts and its pulse orb shows.
	 */
	private void attach(LivingEntity enemy, Vec3 contact) {
		this.attached = true;
		this.enemy = enemy;
		this.velocity = Vec3.ZERO;
		AABB box = enemy.getBoundingBox();
		Vec3 center = box.getCenter();
		if (!WhiplashTargets.heavy(enemy)) {
			this.position = center;
		} else {
			Vec3 in = center.subtract(contact).normalize();
			Vec3 surface = box.clip(contact.subtract(in.scale(S)), center).orElse(center);
			if (surface.distanceToSqr(contact) > 0) {
				this.forward = surface.subtract(contact).normalize();
			}
			this.position = surface;
		}
		this.offset = rotateY(this.position.subtract(enemy.position()), enemy.yBodyRot);
		this.localForward = rotateY(this.forward, enemy.yBodyRot);
		this.sounds.once().update("zapper_attach", 1, this.sounds.randomPitch(2.4F, 2.6F), this.position);
	}

	private void follow(float partialTick) {
		LivingEntity enemy = this.enemy;
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

	/** The pulse orb: from the cable's start toward the zapper, again and again, faster the more it's charged. */
	Vec3 pulsePosition() {
		double t = Math.clamp(this.charge % (0.25 / (this.charge / 4)) * this.charge, 0, 1);
		return this.lineStart.lerp(this.position, t);
	}

	/** The cable's color: gray; reddening near the end of its reach; flashing black and white while it's about to break. */
	float[] cableColor() {
		if (this.breakTimer > 0) {
			return this.breakTimer % FLASH > FLASH / 2 ? BLACK : WHITE;
		}
		double reach = maxDistance();
		if (this.distance > reach - REDDENING) {
			float t = (float)Math.clamp((reach - this.distance) / REDDENING, 0, 1);
			return new float[]{RED[0] + (GRAY[0] - RED[0]) * t, RED[1] + (GRAY[1] - RED[1]) * t, RED[2] + (GRAY[2] - RED[2]) * t, 1};
		}
		return GRAY;
	}

	/** Gone without a trace, as the world it was in is. */
	void discard() {
		this.removed = true;
		this.hum.stop();
		this.pulse.stop();
		this.tighten.stop();
		this.warning.stop();
	}

	/** Zapper.Break: gone, its sounds with it. */
	void destroy(boolean successful, Hits hits) {
		if (this.removed) {
			return;
		}
		this.removed = true;
		this.hum.stop();
		this.pulse.stop();
		this.tighten.stop();
		this.warning.stop();
		hits.zapperBroke(this, this.attached && !successful);
	}
}
