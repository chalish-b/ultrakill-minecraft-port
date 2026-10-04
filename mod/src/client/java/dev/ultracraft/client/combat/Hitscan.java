package dev.ultracraft.client.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/** What a hitscan ray passes through, nearest first, up to the block that stops it. */
final class Hitscan {
	private Hitscan() {
	}

	enum Kind {
		ENTITY,
		COIN,
		/** One of the player's cores, by the trigger sphere around it. */
		CORE,
		BLOCK,
		/** The ray reached its range without hitting a block. */
		END
	}

	/**
	 * @param face the side of the block a {@link Kind#BLOCK} hit struck
	 * @param block the block a {@link Kind#BLOCK} hit struck
	 */
	record Hit(
		Kind kind, Vec3 position, double distance, @Nullable LivingEntity entity, @Nullable Coin coin, @Nullable Core core, boolean head, @Nullable Direction face,
		@Nullable BlockPos block
	) {
	}

	static List<Hit> cast(Level level, Entity shooter, Vec3 from, Vec3 direction, double range, List<Coin> coins) {
		return cast(level, shooter, from, direction, range, coins, List.of());
	}

	static List<Hit> cast(Level level, Entity shooter, Vec3 from, Vec3 direction, double range, List<Coin> coins, List<Core> cores) {
		return cast(level, shooter, from, direction, range, coins, cores, Set.of());
	}

	/** @param broken blocks the ray passes through, as if they were already gone */
	static List<Hit> cast(Level level, Entity shooter, Vec3 from, Vec3 direction, double range, List<Coin> coins, List<Core> cores, Set<BlockPos> broken) {
		Vec3 to = from.add(direction.scale(range));
		BlockHitResult block = clip(level, shooter, from, to, broken);
		boolean blocked = block.getType() != HitResult.Type.MISS;
		Vec3 end = blocked ? block.getLocation() : to;

		List<Hit> hits = new ArrayList<>();
		for (Entity entity : level.getEntities(shooter, new AABB(from, end).inflate(1), e -> e instanceof LivingEntity living && living.isAlive() && !e.isSpectator())) {
			AABB box = entity.getBoundingBox().inflate(entity.getPickRadius());
			Optional<Vec3> clip = box.contains(from) ? Optional.of(from) : box.clip(from, end);
			clip.ifPresent(point -> hits.add(new Hit(Kind.ENTITY, point, point.distanceTo(from), (LivingEntity)entity, null, null, isHead(entity, point), null, null)));
		}
		for (Coin coin : coins) {
			if (!coin.hittable()) {
				continue;
			}
			double along = coin.position.subtract(from).dot(direction);
			if (along < 0 || along > from.distanceTo(end)) {
				continue;
			}
			Vec3 nearest = from.add(direction.scale(along));
			if (nearest.distanceTo(coin.position) <= Coin.HIT_RADIUS) {
				hits.add(new Hit(Kind.COIN, nearest, along, null, coin, null, false, null, null));
			}
		}
		for (Core core : cores) {
			if (core.removed) {
				continue;
			}
			Vec3 offset = core.position.subtract(from);
			double along = offset.dot(direction);
			double off = offset.subtract(direction.scale(along)).length();
			if (off > Core.HIT_RADIUS) {
				continue;
			}
			double entry = along - Math.sqrt(Core.HIT_RADIUS * Core.HIT_RADIUS - off * off);
			if (entry < 0 && offset.length() > Core.HIT_RADIUS || entry > from.distanceTo(end)) {
				continue;
			}
			double at = Math.max(entry, 0);
			hits.add(new Hit(Kind.CORE, from.add(direction.scale(at)), at, null, null, core, false, null, null));
		}
		hits.sort(Comparator.comparingDouble(Hit::distance));
		hits.add(new Hit(blocked ? Kind.BLOCK : Kind.END, end, from.distanceTo(end), null, null, null, false, blocked ? block.getDirection() : null, blocked ? block.getBlockPos() : null));
		return hits;
	}

	/**
	 * A sphere of {@code radius} swept from {@code from} to {@code to} (Physics.SphereCastAll): the entities, coins
	 * and cores it touches, nearest first, each where the sphere first reaches it. Blocks don't stop it.
	 */
	static List<Hit> sweep(Level level, Entity shooter, Vec3 from, Vec3 to, double radius, List<Coin> coins, List<Core> cores) {
		Vec3 direction = to.subtract(from).normalize();
		double length = from.distanceTo(to);
		List<Hit> hits = new ArrayList<>();
		for (Entity entity : level.getEntities(shooter, new AABB(from, to).inflate(radius + 1), e -> e instanceof LivingEntity living && living.isAlive() && !e.isSpectator())) {
			AABB box = entity.getBoundingBox().inflate(entity.getPickRadius());
			AABB reach = box.inflate(radius);
			Optional<Vec3> entry = reach.contains(from) ? Optional.of(from) : reach.clip(from, to);
			if (entry.isEmpty()) {
				continue;
			}
			double distance = entry.get().distanceTo(from);
			Vec3 along = from.add(direction.scale(distance));
			Vec3 point = new Vec3(Math.clamp(along.x, box.minX, box.maxX), Math.clamp(along.y, box.minY, box.maxY), Math.clamp(along.z, box.minZ, box.maxZ));
			hits.add(new Hit(Kind.ENTITY, point, distance, (LivingEntity)entity, null, null, isHead(entity, point), null, null));
		}
		for (Coin coin : coins) {
			if (!coin.hittable()) {
				continue;
			}
			sphereEntry(from, direction, length, coin.position, radius + Coin.HIT_RADIUS).ifPresent(at ->
				hits.add(new Hit(Kind.COIN, from.add(direction.scale(at)), at, null, coin, null, false, null, null))
			);
		}
		for (Core core : cores) {
			if (core.removed) {
				continue;
			}
			sphereEntry(from, direction, length, core.position, radius + Core.HIT_RADIUS).ifPresent(at ->
				hits.add(new Hit(Kind.CORE, from.add(direction.scale(at)), at, null, null, core, false, null, null))
			);
		}
		hits.sort(Comparator.comparingDouble(Hit::distance));
		return hits;
	}

	/** How far along a ray of {@code length} it first comes within {@code reach} of {@code center}, if it does. */
	private static OptionalDouble sphereEntry(Vec3 from, Vec3 direction, double length, Vec3 center, double reach) {
		Vec3 offset = center.subtract(from);
		double along = offset.dot(direction);
		double off = offset.subtract(direction.scale(along)).length();
		if (off > reach) {
			return OptionalDouble.empty();
		}
		double entry = along - Math.sqrt(reach * reach - off * off);
		if (entry < 0 && offset.length() > reach || entry > length) {
			return OptionalDouble.empty();
		}
		return OptionalDouble.of(Math.max(entry, 0));
	}

	/** Level.clip, passing through the {@code broken} blocks. */
	static BlockHitResult clip(Level level, Entity shooter, Vec3 from, Vec3 to, Set<BlockPos> broken) {
		ClipContext context = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter);
		if (broken.isEmpty()) {
			return level.clip(context);
		}
		return BlockGetter.traverseBlocks(from, to, context, (c, pos) -> {
			if (broken.contains(pos)) {
				return null;
			}
			BlockState state = level.getBlockState(pos);
			return level.clipWithInteractionOverride(from, to, pos, c.getBlockShape(state, level, pos), state);
		}, c -> {
			Vec3 delta = from.subtract(to);
			return BlockHitResult.miss(to, Direction.getApproximateNearest(delta.x, delta.y, delta.z), BlockPos.containing(to));
		});
	}

	/** Minecraft mobs have one hitbox; the part around and above eye height counts as the head. */
	static boolean isHead(Entity entity, Vec3 point) {
		double eye = entity.getEyeY();
		double top = entity.getBoundingBox().maxY;
		return point.y >= eye - (top - eye) - 0.1;
	}

	/** Whether nothing solid lies between two points. */
	static boolean clear(Level level, Entity source, Vec3 from, Vec3 to) {
		return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, source)).getType() == HitResult.Type.MISS;
	}
}
