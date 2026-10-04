package dev.ultracraft.client.combat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ultracraft.PlayerShape;
import dev.ultracraft.client.UltraConfig;
import dev.ultracraft.client.movement.UltraSounds;
import dev.ultracraft.client.render.UltraModel;
import dev.ultracraft.combat.WhiplashTargets;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * The Attractor's magnet (Harpoon, carrying a Magnet and a TimeBomb). Thrown, it flies in an arc and sticks in the
 * first living enemy or block its box touches; stuck, its TimeBomb counts down twelve seconds, beeping faster as it
 * goes. All the while, within 25 units it pulls in nails and the airborne enemies nails are stuck in, the more nails
 * the harder, keeps sawblades circling it, and pulls in thrown chainsaws coming toward it. Magnets that touch share
 * what they can hold; holding more than that, it breaks. A revolver beam through it, or a slam landing on it,
 * breaks it too. Breaking, it throws out its nails as a nail bomb, and its sawblades back at where it was.
 */
final class Magnet {
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	/** Time.fixedDeltaTime. */
	private static final double STEP = 0.008;
	/** Physics.gravity, units per second squared. */
	private static final double GRAVITY = 40;
	/** Nailgun.ShootMagnet throws it at this speed. */
	static final double SPEED = 100;
	/** It breaks if it hasn't stuck in anything this long after it's thrown, and in any case this long after. */
	private static final double NOT_HIT_LIFETIME = 5;
	private static final double LIFETIME = 30;
	private static final double FAR_AWAY = 999;
	/** The Harpoon prefab's scale (its root's). */
	static final double MODEL_SCALE = 0.1;
	/** Its Update's pull on a nail is 50 times its strength times how far in the nail is, as a force each frame. */
	private static final double NAIL_PULL = 50;
	/** An enemy is pulled 5 times its strength for every nail in it. */
	private static final double ENEMY_PULL = 5;
	/** ULTRAKILL's enemies' rigidbodies weigh 1; inside a magnet's field, half that. */
	private static final double ENEMY_MASS = 1;
	/** It only pulls thrown chainsaws within this many units of it. */
	private static final double CHAINSAW_REACH = 15;
	/** A chainsaw's sawblade it catches further than this many units away is brought in to this. */
	private static final double SAWBLADE_REACH = 20;
	/** Nails moving at least this fast toward or away from it don't count, and aren't pulled. */
	private static final double TOO_FAST = 1000;
	/** Launch: it throws each nail at an enemy a 5 unit sphere cast up to 100 units out finds, with 10 times its strength as a force. */
	private static final double LAUNCH_RADIUS = 5;
	private static final double LAUNCH_REACH = 100;
	private static final double LAUNCH_FORCE = 10;
	private static final double TRAIL_MIN_DISTANCE = 0.1;
	/** Unity's Color.green and Color.red, which the beeper goes between as it fills up. */
	private static final float[] GREEN = {0, 1, 0, 1};
	private static final float[] RED = {1, 0, 0, 1};

	interface Hits {
		/** How many nails have been stuck in {@code enemy} (EnemyIdentifier.nailsAmount). */
		int nailsIn(LivingEntity enemy);

		/** It pulls {@code enemy}, changing its velocity by this much (units per second). */
		void magnetPull(LivingEntity enemy, Vec3 change);

		/** TimeBomb.Beep: its beeper flashes with a beep. */
		void magnetBeep(Magnet magnet);

		/** It broke: its TimeBomb's explosion, and its nails thrown out. */
		void magnetBroke(Magnet magnet, List<Nail> launched);
	}

	final UltraModel model;
	private final JsonObject harpoon;
	private final JsonObject magnet;
	private final UltraSounds sounds;
	/** The harpoon's root: its tail, which way it points, and its velocity in units per second. */
	Vec3 position;
	Vec3 forward;
	private Vec3 velocity;
	private final Vec3 start;
	private boolean hit;
	private boolean stopped;
	/** The enemy it's stuck in (Magnet.onEnemy), which it doesn't pull, and where its root is in it. */
	private @Nullable LivingEntity onEnemy;
	private Vec3 offset = Vec3.ZERO;
	private Vec3 localForward = Vec3.ZERO;
	/** Its Breakable cube, which revolver beams break it by; gone once it's in a living enemy. */
	private boolean breakable = true;
	/** The nails it holds, and the magnets it has touched, whose room it shares. */
	private final List<Nail> nails = new ArrayList<>();
	private final List<Magnet> connected = new ArrayList<>();
	/** The sawblades and thrown chainsaws in its field. */
	private final List<Sawblade> sawblades = new ArrayList<>();
	private final List<ThrownSaw> chainsaws = new ArrayList<>();
	/** TimeBomb: counting down once it's stuck, beeping after a sixth of what's left each time. */
	private boolean timeBomb;
	private double bombTimer;
	private double beepTimer;
	/** The beeper's size (units, shrinking after each beep), its color, and the beep's pitch, which go up with its load. */
	double beeperScale;
	float[] beeperColor = GREEN;
	double beeperPitch;
	private double beeperSizeMultiplier = 1;
	private double sinceLaunch;
	private double accumulator;
	private double age;
	boolean removed;
	final Deque<Pellet.TrailPoint> trail = new ArrayDeque<>();

	Magnet(UltraModel model, UltraSounds sounds, Vec3 position, Vec3 forward) {
		this.model = model;
		this.harpoon = model.script("harpoon");
		this.magnet = model.script("magnet");
		this.sounds = sounds;
		this.position = position;
		this.start = position;
		this.forward = forward;
		this.velocity = forward.scale(SPEED);
		this.bombTimer = model.script("timeBomb").get("timer").getAsDouble();
		this.beeperPitch = model.script("timeBomb").get("beeperPitch").getAsDouble();
		this.trail.addFirst(new Pellet.TrailPoint(position, 0));
	}

	/** Where the magnet itself is: on its harpoon, or at the middle of the enemy it's stuck in. */
	Vec3 center() {
		if (this.onEnemy != null) {
			return this.onEnemy.getBoundingBox().getCenter();
		}
		int bone = this.magnet.get("bone").getAsInt();
		return this.position.add(this.forward.scale(this.model.rest().pz(bone) * MODEL_SCALE * S));
	}

	/** The field's radius (its sphere's, which Magnet's pull also goes by), in units. */
	private double radius() {
		return this.magnet.get("radius").getAsDouble();
	}

	/** The field's reach in the world (its sphere's radius, scaled with its bones), in blocks. */
	private double reach() {
		double scale = 1;
		for (int bone = this.magnet.get("bone").getAsInt(); bone >= 0; bone = this.model.parent(bone)) {
			scale *= this.model.rest().sx(bone);
		}
		return radius() * scale * S;
	}

	/**
	 * Magnet.Update's pull on a nail it holds, as the acceleration it comes to (units per second squared): toward
	 * the magnet, harder the further in it is and the further off, and none on a nail rushing at or away from it.
	 */
	Vec3 pull(Nail nail) {
		Vec3 toward = center().subtract(nail.position).scale(1 / S);
		if (Math.abs(nail.velocity.dot(toward)) >= TOO_FAST) {
			return Vec3.ZERO;
		}
		double r = radius();
		double strength = this.magnet.get("strength").getAsDouble();
		return toward.scale((r - toward.length()) / r * NAIL_PULL * strength * STEP / nail.mass());
	}

	/**
	 * Magnet.Update's pull on a thrown chainsaw in its field, as the acceleration it comes to (units per second
	 * squared): pulled as a nail is, while it's within 15 units and coming toward the magnet.
	 */
	Vec3 pull(ThrownSaw chainsaw) {
		if (!pullsIn(chainsaw)) {
			return Vec3.ZERO;
		}
		Vec3 toward = center().subtract(chainsaw.position).scale(1 / S);
		double r = radius();
		double strength = this.magnet.get("strength").getAsDouble();
		return toward.scale((r - toward.length()) / r * NAIL_PULL * strength * STEP / chainsaw.mass());
	}

	private boolean pullsIn(ThrownSaw chainsaw) {
		Vec3 toward = center().subtract(chainsaw.position).scale(1 / S);
		Vec3 velocity = chainsaw.velocity();
		return toward.length() < CHAINSAW_REACH && velocity.lengthSqr() > 0 && toward.dot(velocity.normalize()) > 0
			&& Math.abs(velocity.dot(toward)) < TOO_FAST;
	}

	void update(double dt, float partialTick, Level level, LocalPlayer player, List<Nail> allNails, List<Sawblade> allSawblades, List<ThrownSaw> allChainsaws,
		List<Magnet> magnets, Hits hits) {
		this.age += dt;
		this.sinceLaunch += dt;
		this.accumulator += dt;
		while (this.accumulator >= STEP && !this.removed) {
			this.accumulator -= STEP;
			step(level, player);
		}
		if (this.removed) {
			return;
		}
		if (this.onEnemy != null) {
			if (this.onEnemy.isRemoved()) {
				destroy(level, player, hits);
				return;
			}
			follow(partialTick);
		} else if (!this.stopped && this.velocity.length() > 1) {
			this.forward = this.velocity.normalize();
		}
		field(dt, level, player, allNails, allSawblades, allChainsaws, magnets, hits);
		if (this.removed) {
			return;
		}
		updateTimeBomb(dt, level, player, hits);
		if (this.removed) {
			return;
		}
		if (!this.hit && this.sinceLaunch >= NOT_HIT_LIFETIME || this.sinceLaunch >= LIFETIME || this.position.distanceTo(this.start) / S > FAR_AWAY) {
			destroy(level, player, hits);
			return;
		}
		updateTrail();
	}

	/** Its Rigidbody falling, and its trigger box's contacts. */
	private void step(Level level, LocalPlayer player) {
		if (this.stopped) {
			return;
		}
		this.velocity = this.velocity.add(0, -GRAVITY * STEP, 0);
		this.position = this.position.add(this.velocity.scale(STEP * S * UltraConfig.get().speedScale));
		touch(level, player);
	}

	/** Harpoon.OnTriggerEnter for what its box overlaps now, nearest its tail first. */
	private void touch(Level level, LocalPlayer player) {
		OrientedBox box = OrientedBox.of(this.position, this.forward, this.harpoon.getAsJsonObject("box"), MODEL_SCALE * S);
		AABB area = new AABB(box.center(), box.center()).inflate(box.reach(box.center(), this.forward) + 0.5);
		LivingEntity enemy = null;
		double enemyAlong = Double.MAX_VALUE;
		for (Entity entity : level.getEntities(player, area, ThrownSaw::isEnemy)) {
			double along = entity.getBoundingBox().getCenter().subtract(this.position).dot(this.forward);
			if (box.intersects(entity.getBoundingBox()) && along < enemyAlong) {
				enemy = (LivingEntity)entity;
				enemyAlong = along;
			}
		}
		double blockAlong = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(area.minX, area.minY, area.minZ), BlockPos.containing(area.maxX, area.maxY, area.maxZ))) {
			BlockState state = level.getBlockState(pos);
			if (state.isAir()) {
				continue;
			}
			VoxelShape shape = state.getCollisionShape(level, pos);
			if (!shape.isEmpty() && box.intersects(shape.bounds().move(pos))) {
				blockAlong = Math.min(blockAlong, Vec3.atCenterOf(pos).subtract(this.position).dot(this.forward));
			}
		}
		if (enemy != null && enemyAlong <= blockAlong) {
			stickIn(enemy);
		} else if (blockAlong < Double.MAX_VALUE) {
			stickInWall();
		}
	}

	/**
	 * Harpoon on a living enemy: it stays in it and its TimeBomb starts. The magnet moves to the enemy's middle and
	 * leaves it be, and its cube is gone, so it can't be shot off.
	 */
	private void stickIn(LivingEntity enemy) {
		this.hit = true;
		this.stopped = true;
		this.velocity = Vec3.ZERO;
		this.onEnemy = enemy;
		this.breakable = false;
		this.offset = rotateY(this.position.subtract(enemy.position()), enemy.yBodyRot);
		this.localForward = rotateY(this.forward, enemy.yBodyRot);
		this.sounds.once().update("magnet_hit_enemy", 1, this.sounds.randomPitch(0.9F, 1.1F), this.position);
		startTimeBomb();
	}

	/** Harpoon on a wall: it stops there, and its TimeBomb starts. */
	private void stickInWall() {
		this.hit = true;
		this.stopped = true;
		this.velocity = Vec3.ZERO;
		this.sounds.once().update("magnet_hit_wall", 1, this.sounds.randomPitch(0.9F, 1.1F), this.position);
		startTimeBomb();
	}

	private void follow(float partialTick) {
		LivingEntity enemy = this.onEnemy;
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
	 * Magnet's trigger and Update: it catches the nails its sphere reaches and lets go of those that leave it, joins
	 * the magnets it touches, and pulls the airborne enemies in it that have nails in them (Magnet.ProcessEnemyDetection
	 * halves their mass while they're in it). What it holds weighs on it: too much for it and the magnets it's joined,
	 * and it breaks. The beeper goes from green to red, and its beep higher and bigger, as it fills up.
	 */
	private void field(double dt, Level level, LocalPlayer player, List<Nail> allNails, List<Sawblade> allSawblades, List<ThrownSaw> allChainsaws,
		List<Magnet> magnets, Hits hits) {
		Vec3 center = center();
		double reach = reach();
		this.sawblades.removeIf(sawblade -> sawblade.removed);
		for (Sawblade sawblade : allSawblades) {
			if (sawblade.removed) {
				continue;
			}
			boolean inside = sawblade.box().distanceTo(center) < reach;
			if (inside && !this.sawblades.contains(sawblade)) {
				this.sawblades.add(sawblade);
				sawblade.caught(this);
				double distance = sawblade.position.distanceTo(center) / S;
				if (sawblade.chainsaw() && distance > SAWBLADE_REACH) {
					sawblade.position = sawblade.position.add(center.subtract(sawblade.position).normalize().scale((distance - SAWBLADE_REACH) * S));
				}
			} else if (!inside && this.sawblades.contains(sawblade)) {
				this.sawblades.remove(sawblade);
				sawblade.released(this);
			}
		}
		this.chainsaws.removeIf(chainsaw -> chainsaw.removed);
		for (ThrownSaw chainsaw : allChainsaws) {
			if (chainsaw.removed) {
				continue;
			}
			boolean inside = !chainsaw.inPlayer && chainsaw.box().distanceTo(center) < reach;
			if (inside && !this.chainsaws.contains(chainsaw)) {
				this.chainsaws.add(chainsaw);
				chainsaw.magnets.add(this);
			} else if (!inside && this.chainsaws.contains(chainsaw)) {
				this.chainsaws.remove(chainsaw);
				chainsaw.magnets.remove(this);
			}
		}
		for (Nail nail : allNails) {
			if (nail.removed || nail.stuckIn != null) {
				continue;
			}
			boolean inside = nail.position.distanceTo(center) < reach + nail.triggerRadius();
			if (inside && !this.nails.contains(nail)) {
				this.nails.add(nail);
				nail.caught(this);
			} else if (!inside && this.nails.contains(nail)) {
				this.nails.remove(nail);
				nail.released(this);
			}
		}
		for (Magnet other : magnets) {
			if (other != this && !other.removed && !this.connected.contains(other) && other.center().distanceTo(center) < reach + other.reach()) {
				this.connected.add(other);
				other.connected.add(this);
			}
		}
		double weight = 0;
		for (Nail nail : this.nails) {
			Vec3 toward = center.subtract(nail.position).scale(1 / S);
			if (Math.abs(nail.velocity.dot(toward)) < TOO_FAST) {
				weight += nail.mass();
			}
		}
		for (Sawblade sawblade : this.sawblades) {
			weight += sawblade.mass();
		}
		for (ThrownSaw chainsaw : this.chainsaws) {
			if (pullsIn(chainsaw)) {
				weight += chainsaw.mass();
			}
		}
		double r = radius();
		double strength = this.magnet.get("strength").getAsDouble();
		AABB area = new AABB(center, center).inflate(reach);
		for (Entity entity : level.getEntities(player, area, ThrownSaw::isEnemy)) {
			LivingEntity enemy = (LivingEntity)entity;
			int nailsIn = hits.nailsIn(enemy);
			if (enemy == this.onEnemy || nailsIn <= 0 || !movable(enemy) || !touches(enemy.getBoundingBox(), center, reach)) {
				continue;
			}
			double mass = ENEMY_MASS / 2;
			Vec3 toward = center.subtract(enemy.getBoundingBox().getCenter()).scale(1 / S);
			double distance = toward.length();
			if (distance > 0) {
				hits.magnetPull(enemy, toward.scale(1 / distance * (r - distance) / r * nailsIn * ENEMY_PULL * strength * STEP / mass * dt));
			}
			weight += mass;
		}
		double room = this.magnet.get("maxWeight").getAsDouble() * (this.connected.size() + 1);
		if (weight > room) {
			destroy(level, player, hits);
			return;
		}
		double load = weight / room;
		this.beeperColor = new float[]{(float)(GREEN[0] + (RED[0] - GREEN[0]) * load), (float)(GREEN[1] + (RED[1] - GREEN[1]) * load), 0, 1};
		this.beeperPitch = load / 2 + 0.25;
		this.beeperSizeMultiplier = load + 1;
	}

	/**
	 * Whether a magnet moves the enemy. It leaves big enemies be (EnemyIdentifier.bigEnemy), the Whiplash's heavy
	 * ones here, and only moves those whose rigidbodies aren't kinematic: ULTRAKILL's enemies walk the ground
	 * kinematic, and fly or get knocked into the air without.
	 */
	private static boolean movable(LivingEntity enemy) {
		return !WhiplashTargets.heavy(enemy) && (!enemy.onGround() || enemy.isNoGravity());
	}

	private static boolean touches(AABB box, Vec3 center, double radius) {
		double x = Math.clamp(center.x, box.minX, box.maxX) - center.x;
		double y = Math.clamp(center.y, box.minY, box.maxY) - center.y;
		double z = Math.clamp(center.z, box.minZ, box.maxZ) - center.z;
		return x * x + y * y + z * z <= radius * radius;
	}

	/** Its Breakable cube, if it still has it, in the world. */
	@Nullable OrientedBox cube() {
		if (!this.breakable) {
			return null;
		}
		JsonObject breakable = this.model.scripts("breakables").get(0).getAsJsonObject();
		int bone = breakable.get("bone").getAsInt();
		JsonObject box = breakable.getAsJsonObject("box");
		JsonArray center = box.getAsJsonArray("center");
		JsonObject placed = new JsonObject();
		JsonArray offset = new JsonArray();
		offset.add(this.model.rest().px(bone) + center.get(0).getAsDouble());
		offset.add(this.model.rest().py(bone) + center.get(1).getAsDouble());
		offset.add(this.model.rest().pz(bone) + center.get(2).getAsDouble());
		placed.add("center", offset);
		placed.add("size", box.getAsJsonArray("size"));
		return OrientedBox.of(this.position, this.forward, placed, MODEL_SCALE * S);
	}

	/** Its cube hit by a revolver beam (which goes on through) or a slam's landing: it breaks. */
	void hitCube(Level level, LocalPlayer player, Hits hits) {
		if (this.breakable) {
			destroy(level, player, hits);
		}
	}

	/** A nail it holds is gone, or stuck in an enemy. */
	void drop(Nail nail) {
		this.nails.remove(nail);
	}

	private void startTimeBomb() {
		this.timeBomb = true;
		this.beepTimer = 0;
	}

	/** TimeBomb.Update: once started, it counts down and beeps, each time after a sixth of what's left; at 0 it breaks. */
	private void updateTimeBomb(double dt, Level level, LocalPlayer player, Hits hits) {
		this.beeperScale *= Math.max(0, 1 - dt * 5);
		if (!this.timeBomb) {
			return;
		}
		this.bombTimer = Math.max(this.bombTimer - dt, 0);
		this.beepTimer = Math.max(this.beepTimer - dt, 0);
		if (this.beepTimer == 0) {
			this.beepTimer = this.bombTimer / 6;
			this.beeperScale = this.model.script("timeBomb").get("beeperSize").getAsDouble() * this.beeperSizeMultiplier * MODEL_SCALE;
			hits.magnetBeep(this);
		}
		if (this.bombTimer == 0) {
			destroy(level, player, hits);
		}
	}

	/**
	 * Magnet.Launch as it's destroyed: each nail it held stops, and flies off at the nearest enemy a sphere cast out
	 * from the magnet through it finds, or else straight out. Those no other magnet holds go as one nail bomb.
	 */
	void destroy(Level level, LocalPlayer player, Hits hits) {
		if (this.removed) {
			return;
		}
		this.removed = true;
		Vec3 center = center();
		double strength = this.magnet.get("strength").getAsDouble();
		List<Nail> launched = new ArrayList<>();
		for (Nail nail : List.copyOf(this.nails)) {
			if (nail.removed) {
				continue;
			}
			Vec3 out = nail.position.subtract(center);
			Vec3 direction = out.lengthSqr() > 0 ? out.normalize() : this.forward;
			Vec3 target = launchTarget(level, player, nail.position, direction);
			Vec3 aim = target != null && target.distanceToSqr(nail.position) > 0 ? target.subtract(nail.position).normalize() : direction;
			nail.velocity = aim.scale(strength * LAUNCH_FORCE * STEP / nail.mass());
			nail.released(this);
			if (nail.magnets.isEmpty()) {
				launched.add(nail);
			}
		}
		this.nails.clear();
		for (Sawblade sawblade : this.sawblades) {
			sawblade.launched(center);
			sawblade.released(this);
		}
		this.sawblades.clear();
		for (ThrownSaw chainsaw : this.chainsaws) {
			chainsaw.magnets.remove(this);
		}
		this.chainsaws.clear();
		for (Magnet other : this.connected) {
			other.connected.remove(this);
		}
		this.connected.clear();
		hits.magnetBroke(this, launched);
	}

	/** Where a sphere cast for the launch first touches an enemy, if it does. */
	private static @Nullable Vec3 launchTarget(Level level, LocalPlayer player, Vec3 from, Vec3 direction) {
		double radius = LAUNCH_RADIUS * S;
		Vec3 to = from.add(direction.scale(LAUNCH_REACH * S));
		Vec3 nearest = null;
		double best = Double.MAX_VALUE;
		for (Entity entity : level.getEntities(player, new AABB(from, to).inflate(radius + 1), ThrownSaw::isEnemy)) {
			AABB box = entity.getBoundingBox();
			AABB reach = box.inflate(radius);
			Optional<Vec3> entry = reach.contains(from) ? Optional.of(from) : reach.clip(from, to);
			if (entry.isEmpty() || entry.get().distanceTo(from) >= best) {
				continue;
			}
			best = entry.get().distanceTo(from);
			Vec3 along = from.add(direction.scale(best));
			nearest = new Vec3(Math.clamp(along.x, box.minX, box.maxX), Math.clamp(along.y, box.minY, box.maxY), Math.clamp(along.z, box.minZ, box.maxZ));
		}
		return nearest;
	}

	private void updateTrail() {
		double time = this.harpoon.getAsJsonObject("trail").get("time").getAsDouble();
		while (!this.trail.isEmpty() && this.age - this.trail.peekLast().time() > time) {
			this.trail.removeLast();
		}
		if (!this.stopped && (this.trail.isEmpty() || this.position.distanceTo(this.trail.peekFirst().position()) > TRAIL_MIN_DISTANCE * S)) {
			this.trail.addFirst(new Pellet.TrailPoint(this.position, this.age));
		}
	}
}
