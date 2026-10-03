package dev.ultracraft.client.combat;

import dev.ultracraft.PlayerShape;
import dev.ultracraft.client.UltraConfig;
import dev.ultracraft.client.movement.UltraSounds;
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
 * shot on to the next coin or to an enemy's head. It flashes briefly at the top of its arc and charges up later;
 * then it can be punched, and a piercer's charged shot splits on it.
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
	/** TripleTime, TripleTimeEnd and DoubleTime: when the coin flashes, stops flashing, and charges. */
	private static final double FLASH_START = 0.35;
	private static final double FLASH_END = 0.417;
	private static final double CHARGE_START = 1;
	/** The flash effect is removed after this long (its RemoveOnTime). */
	static final double FLASH_LIFE = 0.5;
	/** The charge hum's pitch rises from nothing to this (SoundPitchDip). */
	private static final double CHARGE_PITCH = 2;
	private static final double CHARGE_PITCH_SPEED = 5;

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
	/**
	 * The beam the coin passes the shot on with: a piercing shot's own (Coin's altBeam), or for a single-target
	 * shot the coin's own beam (its refBeam, {@link Effects.BeamStyle#COIN}).
	 */
	Effects.BeamStyle beam = Effects.BeamStyle.COIN;
	/** The coin keeps the camera's rotation from when it was thrown. */
	final float yaw;
	final float pitch;
	/** Trail points, newest first. */
	final Deque<TrailPoint> trail = new ArrayDeque<>();
	boolean removed;
	/** How often a shot passes on from this coin: twice while it flashes or charges, if the shot splits. */
	int hitTimes = 1;
	/** Past its flash: it can be punched. */
	boolean doubled;
	/** Seconds since the flash appeared, or negative while there is none. */
	double flashAge = -1;
	private boolean flashed;
	private boolean flashEnded;
	private boolean charged;
	private double chargeTime;
	private final UltraSounds sounds;
	private final UltraSounds.Loop chargeHum;

	Coin(Vec3 position, Vec3 velocity, float yaw, float pitch, UltraSounds sounds) {
		this.position = position;
		this.velocity = velocity;
		this.yaw = yaw;
		this.pitch = pitch;
		this.sounds = sounds;
		this.chargeHum = sounds.loop();
		this.trail.addFirst(new TrailPoint(position, 0));
	}

	/**
	 * Punchflection's new coin, where the punched one landed: it pops up and starts over, keeping its power and
	 * whether it can be punched or split.
	 */
	Coin respawn(Vec3 position, float power) {
		Coin coin = new Coin(position, new Vec3(0, 25, 0), 0, 0, this.sounds);
		coin.power = power;
		coin.hitTimes = this.hitTimes;
		coin.doubled = this.doubled;
		return coin;
	}

	record TrailPoint(Vec3 position, double time) {
	}

	double age() {
		return this.age;
	}

	boolean hittable() {
		return !this.shot && !this.removed && this.age >= ARM_TIME;
	}

	/** Shot by a beam (or another coin): passes it on after a moment. Its flash and charge timers stop. */
	void hit(float power, Set<Integer> chainHits, Effects.BeamStyle beam) {
		this.shot = true;
		this.power = power;
		this.chainHits = chainHits;
		this.beam = beam;
		this.reflectTimer = REFLECT_DELAY;
	}

	/** Punched: no more flash or charge. */
	void punched() {
		this.shot = true;
		this.flashAge = -1;
		this.chargeHum.stop();
	}

	void remove() {
		this.removed = true;
		this.chargeHum.stop();
	}

	/** Moves the coin, and marks it removed once it expires or comes to rest. */
	void update(double dt, Level level, Entity thrower) {
		this.age += dt;
		if (this.age > LIFETIME) {
			remove();
			return;
		}
		updateTrail();
		updateEffects(dt);
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
				remove();
			}
		} else {
			this.timeToDelete = 1;
		}
	}

	/** TripleTime, TripleTimeEnd and DoubleTime, and the flash and charge effects they bring. */
	private void updateEffects(double dt) {
		if (this.flashAge >= 0) {
			this.flashAge += dt;
			if (this.flashAge > FLASH_LIFE) {
				this.flashAge = -1;
			}
		}
		if (!this.shot && !this.flashed && this.age >= FLASH_START) {
			this.flashed = true;
			this.hitTimes = 2;
			this.doubled = true;
			this.flashAge = 0;
			this.sounds.play("coin_flash", 1, this.sounds.randomPitch(1.3F, 1.4F));
		}
		if (!this.shot && !this.flashEnded && this.age >= FLASH_END) {
			this.flashEnded = true;
			this.hitTimes = 1;
		}
		if (!this.shot && !this.charged && this.age >= CHARGE_START) {
			this.charged = true;
			this.hitTimes = 2;
			this.flashAge = -1;
		}
		if (this.charged && !this.removed) {
			this.chargeTime += dt;
			this.chargeHum.update("coin_charge", 1, Math.min(this.chargeTime * CHARGE_PITCH_SPEED, CHARGE_PITCH));
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
