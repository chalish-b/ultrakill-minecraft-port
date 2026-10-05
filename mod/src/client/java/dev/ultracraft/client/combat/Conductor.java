package dev.ultracraft.client.combat;

import dev.ultracraft.PlayerShape;
import dev.ultracraft.combat.WhiplashTargets;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ULTRAKILL's electricity between enemies (EnemyIdentifier's zaps, Aftershock and Zappable). An electric hit on an
 * enemy with nails or magnets in it, or in water, makes it a conductor: half a second later its nails come out, and
 * its aftershock zaps every enemy within 30 units it can see, those in the air excepted, and everything in the same
 * water, the player too. Magnets, coins, thrown chainsaws and drills it reaches pass the zap on a quarter of a second
 * later. A zap is an electric hit too, so the chain goes on through every conductor it reaches.
 *
 * <p>Water is the water connected to where the zap comes from, within 30 units of it.
 */
final class Conductor {
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	/** A zap reaches enemies within this many units, and those below it within this far down and this far across. */
	private static final double REACH = 30;
	private static final double REACH_BELOW = 60;
	/** A zap does at least this much, or this much to an enemy zapped within the last 5 seconds. */
	private static final float ZAP_DAMAGE = 2;
	private static final float REZAP_DAMAGE = 0.5F;
	private static final double REZAP_TIME = 5;
	/** Through water alone, an enemy zapped within the last second isn't zapped again. */
	private static final double WATER_REZAP_TIME = 1;
	/** EnemyIdentifier.DelayedAfterShock's delay after an electric hit, and after a conductor dies. */
	private static final double AFTERSHOCK_DELAY = 0.5;
	private static final double DEATH_AFTERSHOCK_DELAY = 0.1;
	/** Zappable.Zap and Magnet.Zap: they pass a zap on this long after, at half its damage, and once a second at most. */
	private static final double RELAY_DELAY = 0.25;
	private static final double RELAY_REST = 1;
	private static final float MIN_RELAY_DAMAGE = 0.5F;
	/** A water body is filled in no further than this many blocks. */
	private static final int MAX_WATER_BLOCKS = 65536;

	/** What passes a zap on (Zappable): where it is, and whether it's gone. */
	interface Zappable {
		Vec3 zapPosition();

		boolean zapGone();
	}

	interface Hits {
		/** How many nails have been stuck in {@code enemy} (EnemyIdentifier.nailsAmount). */
		int nailsIn(LivingEntity enemy);

		/** Nail.Zap on the nails in the enemy as it starts conducting. */
		void zapNails(LivingEntity enemy);

		/** EnemyIdentifier.PreShockBleed: the nails in the enemy come out, each with a little blood (and sparks, if zapped). */
		void bleedNails(LivingEntity enemy);

		/** A zap hits {@code enemy} at {@code at} for {@code damage}. */
		void zapEnemy(LivingEntity enemy, float damage, Vec3 at);

		/** A zap through the water the player is in hurts them. */
		void zapPlayer(Vec3 from, Vec3 to);

		/** ElectricityLine: the arc a zap goes along, with its sparks where it lands (zapImpactParticle) if {@code sparks}. */
		void arc(Vec3 from, Vec3 to, boolean sparks);

		/** Magnet.Zap's DamageMagnet: a zap it passed on costs it a point of health. */
		void damageMagnet(Magnet magnet);

		/** EnemyIdentifier.AfterShock's camera shake. */
		void aftershockShake();
	}

	/** What a zap needs to know about the world: the magnets and the other things that pass it on. */
	record World(Level level, LocalPlayer player, List<Magnet> magnets, List<Zappable> zappables) {
	}

	/** An enemy's electric state (EnemyIdentifier's beingZapped, waterOnlyAftershock, hasBeenZapped and lastZapped). */
	private static final class Charge {
		final LivingEntity enemy;
		boolean beingZapped;
		boolean waterOnly;
		boolean hasBeenZapped;
		double lastZapped;
		/** It died while a conductor, and has had its death's aftershock. */
		boolean deathShocked;

		Charge(LivingEntity enemy) {
			this.enemy = enemy;
		}
	}

	/** An aftershock waiting to go off from where its conductor was. */
	private record Aftershock(LivingEntity source, Vec3 position, boolean waterOnly, double at) {
	}

	/** A zap something will pass on, and what the chain it's part of has already reached. */
	private record Relay(Zappable from, float damage, Set<Object> alreadyHit, double at) {
	}

	private final Map<Integer, Charge> charges = new HashMap<>();
	private final List<Aftershock> aftershocks = new ArrayList<>();
	private final List<Relay> relays = new ArrayList<>();
	/** When each zappable can pass a zap on again. */
	private final Map<Zappable, Double> resting = new IdentityHashMap<>();
	private double clock;

	void clear() {
		this.charges.clear();
		this.aftershocks.clear();
		this.relays.clear();
		this.resting.clear();
	}

	/**
	 * EnemyIdentifier.DeliverDamage with electricity, hitter aside from an aftershock's: an enemy with nails or magnets
	 * in it, or in water, starts conducting unless it already is, and its aftershock follows.
	 *
	 * @return whether it started conducting
	 */
	boolean electrify(LivingEntity enemy, World world, Hits hits) {
		Charge charge = charge(enemy);
		int nails = hits.nailsIn(enemy);
		List<Magnet> stuck = stuckMagnets(enemy, world.magnets);
		if (charge.beingZapped || nails <= 0 && stuck.isEmpty() && !enemy.isInWater()) {
			return false;
		}
		charge.beingZapped = true;
		hits.zapNails(enemy);
		charge.waterOnly = nails == 0 && stuck.isEmpty();
		delayedAftershock(charge, AFTERSHOCK_DELAY, world);
		return true;
	}

	/** EnemyIdentifier.DelayedAfterShock: the magnets in it lose a point of health, and the aftershock is on its way. */
	private void delayedAftershock(Charge charge, double delay, World world) {
		for (Magnet magnet : stuckMagnets(charge.enemy, world.magnets)) {
			magnet.weaken();
		}
		this.aftershocks.add(new Aftershock(charge.enemy, charge.enemy.getBoundingBox().getCenter(), charge.waterOnly, this.clock + delay));
	}

	private static List<Magnet> stuckMagnets(LivingEntity enemy, List<Magnet> magnets) {
		List<Magnet> stuck = new ArrayList<>();
		for (Magnet magnet : magnets) {
			if (!magnet.removed && magnet.onEnemy() == enemy) {
				stuck.add(magnet);
			}
		}
		return stuck;
	}

	private Charge charge(LivingEntity enemy) {
		return this.charges.computeIfAbsent(enemy.getId(), id -> new Charge(enemy));
	}

	/** The aftershocks and passed-on zaps that are due; a conductor that died sends one more aftershock (EnemyIdentifier.Death). */
	void update(double dt, World world, Hits hits) {
		this.clock += dt;
		for (Charge charge : List.copyOf(this.charges.values())) {
			if (charge.beingZapped && !charge.deathShocked && !charge.enemy.isAlive()) {
				charge.deathShocked = true;
				delayedAftershock(charge, DEATH_AFTERSHOCK_DELAY, world);
			}
		}
		for (Aftershock aftershock : List.copyOf(this.aftershocks)) {
			if (aftershock.at <= this.clock) {
				this.aftershocks.remove(aftershock);
				aftershock(aftershock, world, hits);
			}
		}
		for (Relay relay : List.copyOf(this.relays)) {
			if (relay.at <= this.clock) {
				this.relays.remove(relay);
				if (!relay.from.zapGone()) {
					zap(relay.from.zapPosition(), relay.damage / 2, relay.alreadyHit, null, null, false, world, hits);
					if (relay.from instanceof Magnet magnet) {
						hits.damageMagnet(magnet);
					}
				}
			}
		}
		this.resting.values().removeIf(until -> until <= this.clock);
		this.charges.values().removeIf(charge -> charge.enemy.isRemoved() && (!charge.beingZapped || charge.deathShocked));
	}

	/**
	 * Aftershock.ZapCoroutine: the conductor's nails come out, it zaps everything around (no more than a zap's
	 * least, as its extra damage is never set), and the conductor can conduct again.
	 */
	private void aftershock(Aftershock aftershock, World world, Hits hits) {
		boolean there = !aftershock.source.isRemoved();
		if (there) {
			hits.bleedNails(aftershock.source);
		}
		Set<Object> alreadyHit = new HashSet<>();
		alreadyHit.add(aftershock.source.getId());
		zap(aftershock.position, 0, alreadyHit, aftershock.source, null, aftershock.waterOnly, world, hits);
		if (there) {
			charge(aftershock.source).beingZapped = false;
			hits.aftershockShake();
		}
	}

	/**
	 * The Electric railcannon's beam through water (RevolverBeam.CheckWater): the water it starts in, and each
	 * other water it goes into on its way, is zapped from there for 2.
	 */
	void beamThroughWater(Vec3 from, Vec3 to, World world, Hits hits) {
		Set<Object> alreadyHit = new HashSet<>();
		Set<BlockPos> zapped = new HashSet<>();
		double length = from.distanceTo(to);
		Vec3 direction = length > 0 ? to.subtract(from).scale(1 / length) : Vec3.ZERO;
		double step = 0.25;
		for (double along = 0; along <= length; along += step) {
			Vec3 at = from.add(direction.scale(along));
			BlockPos block = BlockPos.containing(at);
			if (zapped.contains(block) || !water(world.level, block)) {
				continue;
			}
			Set<BlockPos> body = flood(world.level, List.of(block), at);
			zapped.addAll(body);
			zap(at, ZAP_DAMAGE, alreadyHit, null, body, true, world, hits);
		}
	}

	/**
	 * EnemyIdentifier.Zap from {@code position}: the player, if they're in the water it comes through; every enemy
	 * in that water; and unless it's through water alone, every enemy within reach it can see (in the air only through
	 * water), then the magnets and other zappables within reach it can see, which pass it on.
	 *
	 * @param alreadyHit the enemies (by id) and zappables the chain has already reached
	 * @param sourceEnemy the conductor it comes from, whose water it goes through
	 * @param sourceWater the water it goes through, if it doesn't come from a conductor
	 */
	private void zap(Vec3 position, float damage, Set<Object> alreadyHit, @Nullable LivingEntity sourceEnemy, @Nullable Set<BlockPos> sourceWater, boolean waterOnly,
		World world, Hits hits) {
		Level level = world.level;
		LocalPlayer player = world.player;
		Set<BlockPos> water = sourceWater;
		if (water == null && sourceEnemy != null && sourceEnemy.isInWater()) {
			water = flood(level, waterTouched(level, sourceEnemy.getBoundingBox()), position);
		}
		if (water != null && touches(level, player.getBoundingBox(), water)) {
			hits.zapPlayer(position, player.getBoundingBox().getCenter());
		}
		double reach = REACH * S;
		AABB area = new AABB(position, position).inflate(reach).expandTowards(0, -REACH_BELOW * S, 0);
		for (Entity entity : level.getEntities(player, area, ThrownSaw::isEnemy)) {
			LivingEntity enemy = (LivingEntity)entity;
			if (alreadyHit.contains(enemy.getId())) {
				continue;
			}
			boolean flying = WhiplashTargets.flying(enemy);
			boolean wet = enemy.isInWater();
			if (flying && (sourceWater == null && sourceEnemy == null || !wet)) {
				continue;
			}
			boolean shared = wet && water != null && touches(level, enemy.getBoundingBox(), water);
			if (flying && !shared) {
				continue;
			}
			Charge charge = charge(enemy);
			Vec3 center = enemy.getBoundingBox().getCenter();
			double across = Math.hypot(position.x - center.x, position.z - center.z);
			boolean inReach = position.distanceTo(center) < reach || position.y > center.y && position.y - center.y < REACH_BELOW * S && across < reach;
			if (shared && (!waterOnly || !charge.hasBeenZapped || this.clock - charge.lastZapped > WATER_REZAP_TIME)
				|| !waterOnly && inReach && Hitscan.clear(level, player, position, center)) {
				float hit = Math.max(charge.hasBeenZapped && this.clock - charge.lastZapped < REZAP_TIME ? REZAP_DAMAGE : ZAP_DAMAGE, damage);
				electrify(enemy, world, hits);
				hits.zapEnemy(enemy, hit, center);
				charge.lastZapped = this.clock;
				charge.hasBeenZapped = true;
				hits.arc(position, center, true);
			}
		}
		if (waterOnly) {
			return;
		}
		for (Magnet magnet : world.magnets) {
			LivingEntity on = magnet.onEnemy();
			if (!magnet.removed && !alreadyHit.contains(magnet) && (on == null || !alreadyHit.contains(on.getId())) && reaches(position, magnet, world)) {
				relay(magnet, Math.max(MIN_RELAY_DAMAGE, damage), alreadyHit);
				hits.arc(position, magnet.zapPosition(), false);
			}
		}
		for (Zappable zappable : world.zappables) {
			if (!zappable.zapGone() && !alreadyHit.contains(zappable) && reaches(position, zappable, world)) {
				relay(zappable, Math.max(MIN_RELAY_DAMAGE, damage), alreadyHit);
				hits.arc(position, zappable.zapPosition(), false);
			}
		}
	}

	private static boolean reaches(Vec3 position, Zappable zappable, World world) {
		Vec3 at = zappable.zapPosition();
		return position.distanceTo(at) < REACH * S && Hitscan.clear(world.level, world.player, position, at);
	}

	/** Zappable.Zap: unless it passed a zap on within the last second and a quarter, it's in the chain, and passes this one on. */
	private void relay(Zappable zappable, float damage, Set<Object> alreadyHit) {
		if (this.resting.containsKey(zappable)) {
			return;
		}
		this.resting.put(zappable, this.clock + RELAY_DELAY + RELAY_REST);
		alreadyHit.add(zappable);
		this.relays.add(new Relay(zappable, damage, alreadyHit, this.clock + RELAY_DELAY));
	}

	private static boolean water(Level level, BlockPos block) {
		return level.getFluidState(block).is(FluidTags.WATER);
	}

	/** The water blocks a box is in. */
	private static List<BlockPos> waterTouched(Level level, AABB box) {
		List<BlockPos> touched = new ArrayList<>();
		for (BlockPos block : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
			if (water(level, block)) {
				touched.add(block.immutable());
			}
		}
		return touched;
	}

	private static boolean touches(Level level, AABB box, Set<BlockPos> water) {
		for (BlockPos block : waterTouched(level, box)) {
			if (water.contains(block)) {
				return true;
			}
		}
		return false;
	}

	/** The water connected to {@code from}, out to a zap's reach from {@code center}. */
	private static Set<BlockPos> flood(Level level, List<BlockPos> from, Vec3 center) {
		double reach = REACH * S + 1;
		Set<BlockPos> body = new HashSet<>(from);
		ArrayDeque<BlockPos> open = new ArrayDeque<>(from);
		while (!open.isEmpty() && body.size() < MAX_WATER_BLOCKS) {
			BlockPos block = open.poll();
			for (Direction direction : Direction.values()) {
				BlockPos next = block.relative(direction);
				if (!body.contains(next) && Vec3.atCenterOf(next).distanceTo(center) <= reach && water(level, next)) {
					body.add(next);
					open.add(next);
				}
			}
		}
		return body;
	}
}
