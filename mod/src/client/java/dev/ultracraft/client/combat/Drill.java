package dev.ultracraft.client.combat;

import com.google.gson.JsonObject;
import dev.ultracraft.PlayerShape;
import dev.ultracraft.client.UltraConfig;
import dev.ultracraft.client.movement.UltraSounds;
import dev.ultracraft.combat.ServerCombat;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Screwdriver railcannon's drill (Harpoon, as a drill). It flies in an arc and hits the first enemy it
 * touches: if that kills it, the drill flies on with the damage left over; otherwise it sticks in and drills, a
 * tick at a time, until it runs out of ticks, its TimeBomb runs out or the enemy has been dead a second. A
 * Feedbacker punch on that enemy sends it on again from the camera, straight and refreshed. It breaks through
 * glass, and grinds through other blocks while its durability lasts, each costing its hardness and taking longer
 * the harder it is; a block it can't afford stops it, and it breaks.
 */
final class Drill implements Conductor.Zappable {
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	/** Time.fixedDeltaTime. */
	private static final double STEP = 0.008;
	/** Physics.gravity, units per second squared. */
	private static final double GRAVITY = 40;
	/** Railcannon.Shoot throws it at this speed; a punch at this one, without gravity. */
	static final double SPEED = 250;
	static final double PUNCH_SPEED = 150;
	/** Each drill tick does this much, then it waits this long (in physics steps' time) before the next. */
	static final float TICK_DAMAGE = 0.0625F;
	private static final double TICK_COOLDOWN = 0.05;
	/** A punch does 4 on top of the ticks the drill had left. */
	static final float PUNCH_DAMAGE = 4;
	/** It breaks if it hasn't hit anything this long after a throw or punch, and in any case this long after one. */
	private static final double NOT_HIT_LIFETIME = 5;
	private static final double LIFETIME = 30;
	/** It breaks this long after the enemy it's in has died, and once it's this many units from where it started. */
	private static final double CORPSE_TIME = 1;
	private static final double FAR_AWAY = 999;
	/** Spinning in an enemy, it turns this many degrees a second about its length. */
	private static final double SPIN = 14400;
	/** Blocks' hardness it can break through before it's spent. */
	static final float DURABILITY = 15;
	/** It grinds at a block this long per point of its hardness, cracking it a bit more this often. */
	private static final double GRIND_TIME = 0.25;
	private static final double GRIND_INTERVAL = 0.05;
	/** The model's scale (its root's). */
	static final double MODEL_SCALE = 0.1;
	private static final double TRAIL_MIN_DISTANCE = 0.1;

	interface Hits {
		/** It hit an enemy for {@code damage} at {@code at}. */
		void drillImpact(LivingEntity enemy, float damage, Vec3 at);

		/** A drilling tick on the enemy it's in. */
		void drillTick(LivingEntity enemy, Vec3 at);

		/** It was punched out of an enemy, which takes {@code damage}. */
		void drillPunched(LivingEntity enemy, float damage, Vec3 at);

		/** It hit a block for {@code damage}. */
		void drillBlock(BlockPos block, float damage);

		/** TimeBomb.Beep: its beeper flashes with a beep. */
		void drillBeep(Drill drill);

		/** It broke (Harpoon's breakEffect, and TimeBomb's explosion). */
		void drillBroke(Drill drill);
	}

	final JsonObject script;
	final UltraSounds.Loop drillSound;
	private final UltraSounds sounds;
	Vec3 position;
	/** Units per second. */
	private Vec3 velocity;
	/** Which way its tip points. */
	Vec3 forward;
	/** Degrees it has spun about its length. */
	double spin;
	private final Vec3 start;
	private boolean hit;
	private boolean stopped;
	private boolean drilling;
	private boolean punched;
	private float damageLeft;
	private float durability = DURABILITY;
	private int drillHitsLeft;
	private double drillCooldown;
	/** The enemy it's in (or was last in), and where in it: relative to its position, turned with its body. */
	private @Nullable LivingEntity target;
	private Vec3 offset = Vec3.ZERO;
	private Vec3 localForward = Vec3.ZERO;
	private float targetYaw;
	private double sinceLaunch;
	private double corpseTime = -1;
	/** TimeBomb: counting down once it first sticks in an enemy, beeping faster as it goes. */
	private boolean timeBomb;
	private double bombTimer;
	private double beepTimer;
	/** The beeper's size, shrinking after each beep (units). */
	double beeperScale;
	/** The block it's grinding through, how long it has left at it, and the velocity it carries on with after. */
	private @Nullable BlockPos grinding;
	private float grindHardness;
	private double grindLeft;
	private double sinceGrindCrack;
	private float grindDealt;
	private Vec3 grindVelocity = Vec3.ZERO;
	/** Blocks it has broken, still there until the server says otherwise. */
	private final Set<BlockPos> broken = new HashSet<>();
	private double accumulator;
	private double age;
	boolean removed;
	/** Trail points, newest first; the trail only grows while it flies. */
	final Deque<Pellet.TrailPoint> trail = new ArrayDeque<>();

	/**
	 * @param script the drill's Harpoon
	 * @param bombTime its TimeBomb's timer
	 */
	Drill(JsonObject script, double bombTime, UltraSounds sounds, Vec3 position, Vec3 forward) {
		this.script = script;
		this.sounds = sounds;
		this.drillSound = sounds.loop();
		this.position = position;
		this.start = position;
		this.forward = forward;
		this.velocity = forward.scale(SPEED);
		this.damageLeft = script.get("damage").getAsFloat();
		this.drillHitsLeft = script.get("drillHits").getAsInt();
		this.bombTimer = bombTime;
		this.trail.addFirst(new Pellet.TrailPoint(position, 0));
	}

	double age() {
		return this.age;
	}

	/** Whether it's stuck in {@code enemy}, so a punch on it sends the drill on. */
	boolean stuckIn(Entity enemy) {
		return this.stopped && this.target == enemy && !this.removed;
	}

	/**
	 * @param partialTick how far the game is between ticks, for where the enemy it's in is drawn
	 */
	void update(double dt, float partialTick, Level level, LocalPlayer player, Hits hits) {
		this.age += dt;
		this.sinceLaunch += dt;
		this.accumulator += dt;
		while (this.accumulator >= STEP && !this.removed) {
			this.accumulator -= STEP;
			step(level, player, hits);
		}
		if (this.removed) {
			return;
		}
		if (this.stopped) {
			follow(partialTick);
		} else if (!this.punched && this.velocity.length() > 1) {
			this.forward = this.velocity.normalize();
		}
		if (this.drilling || this.grinding != null) {
			this.spin = (this.spin + SPIN * dt) % 360;
		}
		if (this.corpseTime >= 0) {
			this.corpseTime += dt;
			if (this.corpseTime >= CORPSE_TIME) {
				destroy(hits);
				return;
			}
		}
		updateTimeBomb(dt, hits);
		if (this.removed) {
			return;
		}
		if (!this.hit && this.sinceLaunch >= NOT_HIT_LIFETIME || this.sinceLaunch >= LIFETIME || this.position.distanceTo(this.start) / S > FAR_AWAY) {
			destroy(hits);
			return;
		}
		if (this.drilling) {
			this.drillSound.update("drill_loop", 1, 1.5 - (double)this.drillHitsLeft / this.script.get("drillHits").getAsInt() / 2, this.position);
		} else if (this.grinding != null) {
			this.drillSound.update("drill_loop", 1, 1, this.position);
		} else {
			this.drillSound.stop();
		}
		updateTrail();
	}

	/** Harpoon.FixedUpdate, and the trigger's contacts as it moves. */
	private void step(Level level, LocalPlayer player, Hits hits) {
		if (this.stopped) {
			if (this.drilling && this.target != null && !this.target.isRemoved()) {
				if (this.drillCooldown != 0) {
					this.drillCooldown = Math.max(this.drillCooldown - STEP, 0);
					return;
				}
				this.drillCooldown = TICK_COOLDOWN;
				hits.drillTick(this.target, this.position);
				this.drillHitsLeft--;
				if (this.drillHitsLeft <= 0) {
					destroy(hits);
				}
			} else if (this.drilling) {
				// The enemy is gone: it waits out its second on what's left.
				this.drilling = false;
				this.corpseTime = Math.max(this.corpseTime, 0);
			}
			if (this.target != null && !this.target.isAlive() && this.corpseTime < 0) {
				this.corpseTime = 0;
			}
			return;
		}
		if (this.grinding != null) {
			grind(level, hits);
			return;
		}
		if (!this.punched) {
			this.velocity = this.velocity.add(0, -GRAVITY * STEP, 0);
		}
		this.position = this.position.add(this.velocity.scale(STEP * S * UltraConfig.get().speedScale));
		touch(level, player, hits);
	}

	/** Grinding at a block: it cracks a little at a time, and once it breaks the drill carries on as it was going. */
	private void grind(Level level, Hits hits) {
		BlockPos pos = this.grinding;
		if (level.getBlockState(pos).isAir()) {
			finishGrinding();
			return;
		}
		this.grindLeft -= STEP;
		this.sinceGrindCrack += STEP;
		if (this.grindLeft <= 0) {
			this.broken.add(pos);
			this.durability -= this.grindHardness;
			// What's left of its hardness, and a hair more so the block is sure to go.
			hits.drillBlock(pos, Math.max(this.grindHardness - this.grindDealt, 0) + 0.001F);
			finishGrinding();
		} else if (this.sinceGrindCrack >= GRIND_INTERVAL) {
			this.sinceGrindCrack = 0;
			float total = this.grindHardness;
			float due = (float)(total * (1 - this.grindLeft / (total * GRIND_TIME)));
			if (due > this.grindDealt) {
				hits.drillBlock(pos, due - this.grindDealt);
				this.grindDealt = due;
			}
		}
	}

	private void finishGrinding() {
		this.grinding = null;
		this.velocity = this.grindVelocity;
	}

	/** OnTriggerEnter for what its box overlaps now, nearest its tail first. */
	private void touch(Level level, LocalPlayer player, Hits hits) {
		if (this.grinding != null) {
			return;
		}
		OrientedBox box = OrientedBox.of(this.position, this.forward, this.script.getAsJsonObject("box"), MODEL_SCALE * S);
		AABB area = new AABB(box.center(), box.center()).inflate(box.reach(box.center(), this.forward) + 0.5);
		List<Contact> contacts = new ArrayList<>();
		for (Entity entity : level.getEntities(player, area, ThrownSaw::isEnemy)) {
			if (box.intersects(entity.getBoundingBox())) {
				contacts.add(new Contact(entity.getBoundingBox().getCenter().subtract(this.position).dot(this.forward), (LivingEntity)entity, null));
			}
		}
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(area.minX, area.minY, area.minZ), BlockPos.containing(area.maxX, area.maxY, area.maxZ))) {
			if (this.broken.contains(pos)) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			if (state.isAir()) {
				continue;
			}
			VoxelShape shape = state.getCollisionShape(level, pos);
			if (!shape.isEmpty() && box.intersects(shape.bounds().move(pos))) {
				contacts.add(new Contact(Vec3.atCenterOf(pos).subtract(this.position).dot(this.forward), null, pos.immutable()));
			}
		}
		contacts.sort(Comparator.comparingDouble(Contact::along));
		for (Contact contact : contacts) {
			if (this.hit || this.removed || this.grinding != null) {
				return;
			}
			if (contact.enemy != null) {
				touchEnemy(contact.enemy, hits);
			} else {
				touchBlock(level, contact.block, hits);
			}
		}
	}

	private record Contact(double along, @Nullable LivingEntity enemy, @Nullable BlockPos block) {
	}

	/**
	 * Harpoon on an enemy: it takes what damage the drill has left. One that dies of it lets the drill through,
	 * the poorer by its health; one that lives keeps it, and the drilling starts. It won't hit the enemy it was
	 * punched out of.
	 */
	private void touchEnemy(LivingEntity enemy, Hits hits) {
		if (enemy == this.target) {
			return;
		}
		float health = (enemy.getHealth() + enemy.getAbsorptionAmount()) / ServerCombat.HEALTH_PER_DAMAGE;
		hits.drillImpact(enemy, this.damageLeft, this.position);
		this.sounds.once().update("drill_hit_enemy", 1, this.sounds.randomPitch(0.9F, 1.1F), this.position);
		if (health < this.damageLeft) {
			this.damageLeft -= health;
			this.target = enemy;
			return;
		}
		this.target = enemy;
		this.hit = true;
		this.stopped = true;
		this.drilling = true;
		this.velocity = Vec3.ZERO;
		this.targetYaw = enemy.yBodyRot;
		this.offset = rotateY(this.position.subtract(enemy.position()), this.targetYaw);
		this.localForward = rotateY(this.forward, this.targetYaw);
		this.timeBomb = true;
		beep(hits);
	}

	/**
	 * A block in its way: glass breaks for free; another block it can afford breaks, costing its hardness; one it
	 * can't afford takes what's left, and the drill breaks.
	 */
	private void touchBlock(Level level, BlockPos pos, Hits hits) {
		BlockState state = level.getBlockState(pos);
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness >= 0 && state.getSoundType() == SoundType.GLASS) {
			this.broken.add(pos);
			hits.drillBlock(pos, hardness);
			return;
		}
		if (hardness == 0) {
			this.broken.add(pos);
			hits.drillBlock(pos, 0.001F);
			return;
		}
		if (hardness > 0 && hardness <= this.durability) {
			this.grinding = pos;
			this.grindHardness = hardness;
			this.grindLeft = hardness * GRIND_TIME;
			this.sinceGrindCrack = 0;
			this.grindDealt = 0;
			this.grindVelocity = this.velocity;
			this.velocity = Vec3.ZERO;
			return;
		}
		if (this.durability > 0) {
			hits.drillBlock(pos, this.durability);
		}
		destroy(hits);
	}

	/** Stuck in its enemy, it goes where the enemy is drawn, turning with it. */
	private void follow(float partialTick) {
		LivingEntity enemy = this.target;
		if (enemy == null || enemy.isRemoved()) {
			return;
		}
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

	/**
	 * Harpoon.Punched: out of the enemy and off from the camera along the aim, straight and refreshed. The enemy
	 * takes the ticks the drill had left at once, and 4 more.
	 */
	void punched(Vec3 from, Vec3 direction, Hits hits) {
		LivingEntity enemy = this.target;
		if (enemy != null) {
			hits.drillPunched(enemy, PUNCH_DAMAGE + this.drillHitsLeft * TICK_DAMAGE, this.position);
		}
		this.hit = false;
		this.stopped = false;
		this.drilling = false;
		this.punched = true;
		this.corpseTime = -1;
		this.damageLeft = this.script.get("damage").getAsFloat();
		this.durability = DURABILITY;
		this.drillHitsLeft = this.script.get("drillHits").getAsInt();
		this.drillCooldown = 0;
		this.sinceLaunch = 0;
		this.position = from;
		this.forward = direction;
		this.velocity = direction.scale(PUNCH_SPEED);
		this.drillSound.stop();
		this.trail.clear();
		this.trail.addFirst(new Pellet.TrailPoint(from, this.age));
	}

	/** TimeBomb: once started, it counts down and beeps, each time after a sixth of what's left; at 0 the drill breaks. */
	private void updateTimeBomb(double dt, Hits hits) {
		this.beeperScale *= Math.max(0, 1 - dt * 5);
		if (!this.timeBomb) {
			return;
		}
		this.bombTimer = Math.max(this.bombTimer - dt, 0);
		this.beepTimer = Math.max(this.beepTimer - dt, 0);
		if (this.beepTimer == 0) {
			beep(hits);
		}
		if (this.bombTimer == 0) {
			destroy(hits);
		}
	}

	private void beep(Hits hits) {
		this.beepTimer = this.bombTimer / 6;
		hits.drillBeep(this);
	}

	private void destroy(Hits hits) {
		if (this.removed) {
			return;
		}
		this.removed = true;
		this.drillSound.stop();
		hits.drillBroke(this);
	}

	private void updateTrail() {
		double time = this.script.getAsJsonObject("trail").get("time").getAsDouble();
		while (!this.trail.isEmpty() && this.age - this.trail.peekLast().time() > time) {
			this.trail.removeLast();
		}
		if (!this.stopped && (this.trail.isEmpty() || this.position.distanceTo(this.trail.peekFirst().position()) > TRAIL_MIN_DISTANCE * S)) {
			this.trail.addFirst(new Pellet.TrailPoint(this.position, this.age));
		}
	}

	@Override
	public Vec3 zapPosition() {
		return this.position;
	}

	@Override
	public boolean zapGone() {
		return this.removed;
	}
}
