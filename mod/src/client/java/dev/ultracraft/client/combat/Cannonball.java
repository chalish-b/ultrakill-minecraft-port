package dev.ultracraft.client.combat;

import dev.ultracraft.PlayerShape;
import dev.ultracraft.client.UltraConfig;
import dev.ultracraft.combat.BlockDamage;
import dev.ultracraft.combat.ServerCombat;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The S.R.S. Cannon's cannonball (Cannonball, a physics cannonball): it arcs under gravity, hitting the enemies it
 * touches harder the faster it goes. An enemy it doesn't kill bounces it up and back, once; a second one, or one it
 * meets too slowly, breaks it. A wall breaks it, sending out a shockwave if it's the ground.
 *
 * <p>Punched, it flies straight on at full speed: it goes through the enemies it kills, up to its durability, and
 * breaks on the first one that lives. Punched after a bounce, it hits harder and only a wall or an enemy that lives
 * stops it. The Whiplash carries it and hands it back. Revolver shots, coins and railcannon beams make it explode.
 *
 * <p>Meeting a wall it can break into, it bores through it as the Electric railcannon's beam does, as wide as it
 * is: through the blocks its damage breaks, deeper the faster it hits, a few blocks at most all told (punched, twice
 * as hard and much deeper), and it breaks where its tunnel stops.
 */
final class Cannonball extends Grenade {
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	/** Physics.gravity, units per second squared. */
	private static final double GRAVITY = 40;
	/** Cannonball.damage: what a hit at full speed does. */
	private static final float DAMAGE = 5;
	/** Cannonball.speed: punched, it flies at this many units a second. */
	private static final double SPEED = 150;
	/** Cannonball.durability: punched, it goes through this many enemies it kills. */
	private static final int DURABILITY = 2;
	/** Cannonball.maxBounces, counting punches. */
	private static final int MAX_BOUNCES = 1;
	/** Its trigger sphere's radius, units. */
	static final double RADIUS = 1;
	/** Its InterruptHelper and ParryHelper spheres, which shots, coins, punches and the Whiplash find it by: radius 0.5 at scale 4. */
	static final double HIT_RADIUS = 2;
	/** Punched after its bounce, it does this much more. */
	private static final float BOUNCED_PUNCH_DAMAGE = 2;
	/** Unpunched, it hurts only faster than this, by this much per unit a second, up to its damage. */
	private static final double MIN_DAMAGE_SPEED = 10;
	private static final double DAMAGE_PER_SPEED = 0.15;
	/** An enemy it doesn't kill bounces it at this speed or faster, and breaks it slower. */
	private static final double BOUNCE_SPEED = 15;
	/** A bounce sends it up at this share of its speed, and back at this speed. */
	private static final double BOUNCE_UP_SHARE = 0.15;
	private static final double BOUNCE_BACK = 5;
	/** After a bounce it can go through any number of enemies it kills. */
	private static final int BOUNCED_DURABILITY = 99;
	/** For this long after a bounce, enemies it touches are only marked as hit (instaBreakDefence). */
	private static final double INSTA_BREAK_DEFENCE = 1;
	/** An enemy it hit can be hit again once they're this far apart (units). */
	private static final double FORGET_DISTANCE = 20;
	/** A surface within this many degrees of flat is ground, which it sends a shockwave across. */
	private static final double GROUND_ANGLE = 45;
	/** The shockwave's force: this, and this much per unit a second it lands at. */
	private static final double SHOCKWAVE_FORCE = 10000;
	private static final double SHOCKWAVE_FORCE_PER_SPEED = 80;
	/** The shockwave starts this far off the ground (units). */
	private static final double SHOCKWAVE_LIFT = 0.1;
	/** Explosion.Collide's push on a loose rigidbody under gravity (forces, for one physics step at unit mass). */
	private static final double EXPLOSION_PUSH_SIDE = 7500;
	private static final double EXPLOSION_PUSH_UP = 18750;
	private static final double EXPLOSION_PUSH_REACH = 5;
	private static final double STEP = 0.008;
	/** A set direction (playerProjectileForceDirection) sends it that way at this speed. */
	private static final double EXPLOSION_THROW_SPEED = 100;
	/**
	 * Its tunnels go through this many blocks all told, at its full speed and fewer the slower it hits; punched, this
	 * many, at this many times the damage. With less than a block left, it only hits the block it meets.
	 */
	private static final double THROWN_TUNNEL_DEPTH = 6;
	private static final double PUNCHED_TUNNEL_DEPTH = 12;
	private static final float PUNCHED_BLOCK_DAMAGE = 2;
	/** Its world's bottom: falling this far below it, it's gone (FloatingPointErrorPreventer). */
	private static final double VOID_DEPTH = 64;
	/** How long a point of its trail lasts, and how far apart they're laid (its TrailRenderer's). */
	static final double TRAIL_TIME = 0.25;
	private static final double TRAIL_MIN_DISTANCE = 0.1;

	/** What becomes of a cannonball, and what it does. */
	interface Hits {
		/** It hit {@code enemy} for {@code damage} at {@code at}, pushing it along {@code push} (units a second). */
		void cannonballHitEnemy(LivingEntity enemy, float damage, Vec3 push, Vec3 at);

		/** It hit a block for {@code damage}. */
		void cannonballHitBlock(BlockPos block, float damage);

		/** An enemy it didn't kill bounced it. */
		void cannonballBounced(Cannonball ball);

		/** It landed, and its shockwave goes out from {@code at} with {@code force}. */
		void cannonballLanded(Cannonball ball, Vec3 at, double force);

		/** It broke apart. */
		void cannonballBroke(Cannonball ball);
	}

	/** Units per second. */
	private Vec3 velocity;
	/** Which way it faces (transform.forward): it flies this way once punched. */
	Vec3 forward;
	/** Punched: it flies straight on, without gravity. */
	boolean launched;
	private boolean launchable = true;
	/** Caught by the Whiplash, it hits at full damage whatever its speed, until the hook sends it back. */
	private boolean forceMaxSpeed;
	private boolean hasBounced;
	private int currentBounces;
	private int durability = DURABILITY;
	private float damage = DAMAGE;
	private double sinceBounce = INSTA_BREAK_DEFENCE;
	/** The enemies it has hit, which it doesn't hit again until they're far apart. */
	private final Map<Integer, LivingEntity> hitEnemies = new HashMap<>();
	/** The blocks its tunnels went through, which it flies on through as if they were already gone. */
	private final Set<BlockPos> bored = new HashSet<>();
	/** The block that stopped its tunnel, where it breaks once it gets there, and which way the tunnel goes. */
	private Hitscan.@Nullable Hit tunnelEnd;
	private Vec3 tunnelDirection = Vec3.ZERO;
	/** How deep its tunnels have gone, in blocks, since it was thrown or last punched. */
	private double depthBored;
	/** The Whiplash just let go of it where it stopped: it hits what it's in before it moves on. */
	private boolean dropped;
	private double age;
	/** Trail points, newest first. */
	final Deque<Pellet.TrailPoint> trail = new ArrayDeque<>();

	Cannonball(Vec3 position, Vec3 velocity, Vec3 forward) {
		super(position);
		this.velocity = velocity;
		this.forward = forward;
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
		return HIT_RADIUS * S;
	}

	/** Its velocity is set. */
	@Override
	void launch(Vec3 velocity) {
		this.velocity = velocity;
		this.tunnelEnd = null;
	}

	/** Whether the Feedbacker can launch it (Cannonball.launchable). */
	boolean punchable() {
		return this.launchable;
	}

	/** Whether it falls (and explosions push it): it does until it's punched. */
	boolean underGravity() {
		return !this.launched;
	}

	/**
	 * Cannonball.Launch, as the Feedbacker punches it from {@code position} along {@code forward}: it flies straight on
	 * at full speed, ready to hit every enemy again, and harder if it has bounced.
	 *
	 * @return whether it could be punched
	 */
	boolean punched(Vec3 position, Vec3 forward) {
		if (!this.launchable) {
			return false;
		}
		this.position = position;
		this.forward = forward;
		this.launched = true;
		this.hitEnemies.clear();
		this.sinceBounce = INSTA_BREAK_DEFENCE;
		if (this.currentBounces == 1 && this.hasBounced) {
			this.damage += BOUNCED_PUNCH_DAMAGE;
		}
		this.currentBounces++;
		this.tunnelEnd = null;
		this.depthBored = 0;
		return true;
	}

	/** HookArm catches it (Cannonball.Unlaunch): it stops, and hits at full damage while the hook has it. */
	void caught() {
		this.hooked = true;
		this.launchable = true;
		this.launched = false;
		this.velocity = Vec3.ZERO;
		this.forceMaxSpeed = true;
		this.sinceBounce = INSTA_BREAK_DEFENCE;
	}

	/** The hook lets it go toward the player at {@code velocity}: it can hit every enemy again, at its speed's damage. */
	void sentBack(Vec3 velocity) {
		this.hooked = false;
		this.velocity = velocity;
		this.hitEnemies.clear();
		this.forceMaxSpeed = false;
	}

	/** The hook stopped on something and lets go of it there, as it is. */
	void dropped() {
		this.hooked = false;
		this.dropped = true;
	}

	/** Whether its sphere, where it is, touches {@code enemy}. */
	boolean reaches(LivingEntity enemy) {
		double radius = RADIUS * S;
		return closest(enemy.getBoundingBox(), this.position).distanceToSqr(this.position) <= radius * radius;
	}

	/** Whether it has hit {@code enemy} and not yet got far enough from it to hit it again. */
	boolean hasHit(LivingEntity enemy) {
		return this.hitEnemies.containsKey(enemy.getId());
	}

	/**
	 * Explosion.Collide on it, while it falls: a set direction sends it that way; otherwise it's thrown up hard,
	 * and outward the closer it is.
	 */
	void explosionPush(Vec3 origin, @Nullable Vec3 forceDirection) {
		if (forceDirection != null) {
			this.velocity = forceDirection.scale(EXPLOSION_THROW_SPEED);
			return;
		}
		Vec3 away = this.position.subtract(origin);
		double distance = away.length() / S;
		Vec3 direction = distance > 0 ? away.normalize() : Vec3.ZERO;
		double side = Math.max(EXPLOSION_PUSH_REACH - distance, 0) * EXPLOSION_PUSH_SIDE * STEP;
		this.velocity = this.velocity.add(direction.x * side, EXPLOSION_PUSH_UP * STEP, direction.z * side);
	}

	/**
	 * Cannonball.FixedUpdate and its trigger: punched, it flies on at full speed, else it falls. On its way it hits
	 * the enemies its sphere touches, nearest first, and stops at the first block it meets. The Whiplash moves it
	 * while it has it; it still hits what it's carried into.
	 */
	void update(double dt, Level level, LocalPlayer player, Hits hits) {
		this.age += dt;
		this.sinceBounce += dt;
		updateTrail();
		this.hitEnemies.values().removeIf(e -> e.isRemoved() || e.position().distanceTo(this.position) > FORGET_DISTANCE * S);
		if (this.position.y < level.getMinY() - VOID_DEPTH) {
			this.removed = true;
			return;
		}
		if (this.hooked || this.dropped) {
			this.dropped = false;
			if (touchWhereItIs(level, player, hits) || this.hooked) {
				return;
			}
		}
		if (this.launched) {
			this.velocity = this.forward.scale(SPEED);
		} else {
			this.velocity = this.velocity.add(0, -GRAVITY * dt, 0);
		}
		Vec3 next = this.position.add(this.velocity.scale(dt * S * UltraConfig.get().speedScale));
		// In a tunnel that has an end, it flies on to that end, as the beam it bores like goes through what's in it.
		BlockHitResult wall = this.tunnelEnd == null ? firstBlock(level, this.position, next) : null;
		Vec3 from = this.position;
		Vec3 end = wall != null ? wall.getLocation() : next;
		Vec3 direction = end.subtract(from).normalize();
		boolean tunnelOver = false;
		if (this.tunnelEnd != null) {
			// Its sphere reaches the block that stopped its tunnel.
			Vec3 stop = this.tunnelEnd.position().subtract(this.tunnelDirection.scale(RADIUS * S));
			double along = stop.subtract(from).dot(direction);
			if (along <= end.distanceTo(from)) {
				end = from.add(direction.scale(Math.max(along, 0)));
				tunnelOver = true;
			}
		}
		for (Touch touch : enemiesAlong(level, player, from, end)) {
			this.position = from.add(direction.scale(touch.distance()));
			if (collide(touch.enemy(), hits)) {
				return;
			}
		}
		this.position = end;
		if (tunnelOver) {
			Hitscan.Hit stop = this.tunnelEnd;
			stopAt(stop.position(), stop.face().getUnitVec3(), hits);
		} else if (wall != null) {
			meetWall(level, player, wall, hits);
		}
	}

	/**
	 * Where the Whiplash carries or drops it: it hits the enemies and the block its sphere is in.
	 *
	 * @return whether it stopped: it bounced or broke
	 */
	private boolean touchWhereItIs(Level level, LocalPlayer player, Hits hits) {
		for (Touch touch : enemiesAlong(level, player, this.position, this.position)) {
			if (collide(touch.enemy(), hits)) {
				return true;
			}
		}
		BlockPos block = blockIn(level);
		if (block != null) {
			float damage = blockDamage();
			hits.cannonballHitBlock(block, damage);
			if (damage > 0 && BlockDamage.breaksInOneHit(level, block, damage)) {
				this.bored.add(block);
			} else {
				breakApart(hits);
			}
		}
		return this.removed;
	}

	/**
	 * Cannonball.Collide on an enemy it hasn't hit. Just after a bounce it only marks it as hit. Otherwise it hurts
	 * it (and knocks it along its velocity), if it's fast enough or on the hook. An enemy it kills costs it some of
	 * its durability, and bounces it if it hasn't been punched; one that lives bounces it, or breaks it if it's slow.
	 *
	 * @return whether it stopped: it bounced or broke
	 */
	boolean collide(LivingEntity enemy, Hits hits) {
		if (this.removed || hasHit(enemy)) {
			return false;
		}
		this.hitEnemies.put(enemy.getId(), enemy);
		if (this.sinceBounce < INSTA_BREAK_DEFENCE) {
			return false;
		}
		double speed = this.velocity.length();
		float damage = 0;
		Vec3 push = Vec3.ZERO;
		if (this.forceMaxSpeed) {
			damage = this.damage;
			// DeliverDamage's force (forward × 1000) comes to a thousandth of it as speed.
			push = this.forward;
		} else if (speed > MIN_DAMAGE_SPEED) {
			damage = (float)Math.min(this.damage, speed * DAMAGE_PER_SPEED);
			push = this.velocity;
		}
		boolean killed = damage > 0 && enemy.getHealth() + enemy.getAbsorptionAmount() <= damage * ServerCombat.HEALTH_PER_DAMAGE;
		if (damage > 0) {
			hits.cannonballHitEnemy(enemy, damage, push, closest(enemy.getBoundingBox(), this.position));
		}
		if (killed) {
			if (--this.durability <= 0) {
				breakApart(hits);
				return true;
			}
			if (!this.launched) {
				bounce(hits);
				return true;
			}
			return false;
		}
		if (speed < BOUNCE_SPEED) {
			breakApart(hits);
		} else {
			bounce(hits);
		}
		return true;
	}

	/**
	 * Cannonball.Bounce: once (a punch counting as one), it's sent up and back under gravity, ready to be punched,
	 * through any number of enemies; past that it breaks.
	 */
	private void bounce(Hits hits) {
		if (this.currentBounces >= MAX_BOUNCES) {
			breakApart(hits);
			return;
		}
		this.sinceBounce = 0;
		this.currentBounces++;
		this.durability = BOUNCED_DURABILITY;
		this.hasBounced = true;
		this.launched = false;
		this.launchable = true;
		this.tunnelEnd = null;
		double speed = this.velocity.length();
		Vec3 back = speed > 0 ? this.velocity.scale(-BOUNCE_BACK / speed) : Vec3.ZERO;
		this.velocity = new Vec3(0, speed * BOUNCE_UP_SHARE, 0).add(back);
		hits.cannonballBounced(this);
	}

	/** Cannonball.Break. */
	void breakApart(Hits hits) {
		if (this.removed) {
			return;
		}
		this.removed = true;
		hits.cannonballBroke(this);
	}

	/**
	 * It met a wall. If it can break the block it touched and has a block or more of depth left, it bores its tunnel
	 * from where it is along its way with what's left, hitting every block in it, and flies on through it to the
	 * block that stops it; otherwise it hits that block and stops there.
	 */
	private void meetWall(Level level, LocalPlayer player, BlockHitResult wall, Hits hits) {
		float damage = blockDamage();
		BlockPos touched = wall.getBlockPos();
		double speed = this.velocity.length();
		double depth = (this.launched ? PUNCHED_TUNNEL_DEPTH : THROWN_TUNNEL_DEPTH * Math.min(speed / SPEED, 1)) - this.depthBored;
		if (!(damage > 0) || depth < 1 || !BlockDamage.breaksInOneHit(level, touched, damage)) {
			hits.cannonballHitBlock(touched, damage);
			stopAt(wall.getLocation(), wall.getDirection().getUnitVec3(), hits);
			return;
		}
		Vec3 direction = this.velocity.normalize();
		double radius = RADIUS * S;
		// The tunnel's depth counts from its first block: that one and the rest make up the blocks it has left.
		Tunnel tunnel = Tunnel.bore(level, player, this.position, direction, 2 * radius + depth + 1, damage, radius, depth - 1);
		// Its sphere touched a block its middle passes by: that one goes too.
		if (tunnel.blocks().stream().noneMatch(hit -> hit.block().equals(touched))) {
			hits.cannonballHitBlock(touched, damage);
			this.bored.add(touched);
		}
		this.tunnelEnd = null;
		double nearest = Double.MAX_VALUE;
		double farthest = 0;
		for (Hitscan.Hit hit : tunnel.blocks()) {
			hits.cannonballHitBlock(hit.block(), damage);
			this.bored.add(hit.block());
			if (hit.face() != null) {
				this.tunnelEnd = hit;
			} else {
				nearest = Math.min(nearest, hit.distance());
				farthest = Math.max(farthest, hit.distance());
			}
		}
		this.tunnelDirection = direction;
		// The tunnel took up the depth from its first block through its last, a block at least.
		this.depthBored += Math.max(farthest - nearest + 1, 1);
	}

	/** It stops against a block's {@code normal} side at {@code at} and breaks, sending out its shockwave if that's ground. */
	private void stopAt(Vec3 at, Vec3 normal, Hits hits) {
		double speed = this.velocity.length();
		if (speed > 0 && Math.toDegrees(Math.acos(Math.clamp(normal.y, -1, 1))) < GROUND_ANGLE) {
			hits.cannonballLanded(this, at.add(normal.scale(SHOCKWAVE_LIFT * S)), SHOCKWAVE_FORCE + speed * SHOCKWAVE_FORCE_PER_SPEED);
		}
		breakApart(hits);
	}

	/** Its damage to blocks: what it does to enemies at its speed, and twice that punched. */
	private float blockDamage() {
		if (this.launched) {
			return this.damage * PUNCHED_BLOCK_DAMAGE;
		}
		double speed = this.velocity.length();
		return speed > MIN_DAMAGE_SPEED ? (float)Math.min(this.damage, speed * DAMAGE_PER_SPEED) : 0;
	}

	/** The first block (but those its tunnels went through) its sphere meets on the way from {@code from} to {@code to}, or null. */
	private @Nullable BlockHitResult firstBlock(Level level, Vec3 from, Vec3 to) {
		double radius = RADIUS * S;
		AABB swept = new AABB(from, to).inflate(radius);
		BlockHitResult first = null;
		double nearest = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(swept.minX, swept.minY, swept.minZ), BlockPos.containing(swept.maxX, swept.maxY, swept.maxZ))) {
			if (this.bored.contains(pos)) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			if (state.isAir()) {
				continue;
			}
			// In the block's own space, as AABB.clip places them at the block.
			List<AABB> boxes = state.getCollisionShape(level, pos).toAabbs().stream().map(box -> box.inflate(radius)).toList();
			if (boxes.isEmpty() || boxes.stream().anyMatch(box -> box.move(pos).contains(from))) {
				continue;
			}
			BlockHitResult hit = AABB.clip(boxes, from, to, pos.immutable());
			if (hit == null) {
				continue;
			}
			// Blocks side by side are met at once; the one it hits is the one nearest its middle.
			double distance = hit.getLocation().distanceToSqr(from);
			if (distance < nearest - 1.0E-9 || distance < nearest + 1.0E-9 && first != null
				&& Vec3.atCenterOf(pos).distanceToSqr(hit.getLocation()) < Vec3.atCenterOf(first.getBlockPos()).distanceToSqr(first.getLocation())) {
				nearest = Math.min(nearest, distance);
				first = hit;
			}
		}
		return first;
	}

	/** A block (but one its tunnels went through) its sphere is in, the nearest to its middle, or null. */
	private @Nullable BlockPos blockIn(Level level) {
		double radius = RADIUS * S;
		AABB sphere = new AABB(this.position, this.position).inflate(radius);
		BlockPos nearest = null;
		double best = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(sphere.minX, sphere.minY, sphere.minZ), BlockPos.containing(sphere.maxX, sphere.maxY, sphere.maxZ))) {
			if (this.bored.contains(pos)) {
				continue;
			}
			for (AABB box : level.getBlockState(pos).getCollisionShape(level, pos).move(pos).toAabbs()) {
				double distance = closest(box, this.position).distanceToSqr(this.position);
				if (distance <= radius * radius && distance < best) {
					best = distance;
					nearest = pos.immutable();
				}
			}
		}
		return nearest;
	}

	/** An enemy its sphere reaches {@code distance} along its way (blocks). */
	private record Touch(LivingEntity enemy, double distance) {
	}

	/** The living enemies its sphere touches on its way from {@code from} to {@code to}, nearest first. */
	private static List<Touch> enemiesAlong(Level level, LocalPlayer player, Vec3 from, Vec3 to) {
		double radius = RADIUS * S;
		List<Touch> touches = new ArrayList<>();
		for (Entity entity : level.getEntities(player, new AABB(from, to).inflate(radius), e -> e instanceof LivingEntity living && living.isAlive() && !e.isSpectator())) {
			AABB reach = entity.getBoundingBox().inflate(radius);
			Optional<Vec3> entry = reach.contains(from) ? Optional.of(from) : reach.clip(from, to);
			entry.ifPresent(at -> touches.add(new Touch((LivingEntity)entity, at.distanceTo(from))));
		}
		touches.sort(Comparator.comparingDouble(Touch::distance));
		return touches;
	}

	private static Vec3 closest(AABB box, Vec3 point) {
		return new Vec3(Math.clamp(point.x, box.minX, box.maxX), Math.clamp(point.y, box.minY, box.maxY), Math.clamp(point.z, box.minZ, box.maxZ));
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
