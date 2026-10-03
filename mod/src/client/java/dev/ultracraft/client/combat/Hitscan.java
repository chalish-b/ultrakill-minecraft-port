package dev.ultracraft.client.combat;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** What a hitscan ray passes through, nearest first, up to the block that stops it. */
final class Hitscan {
	private Hitscan() {
	}

	enum Kind {
		ENTITY,
		COIN,
		BLOCK,
		/** The ray reached its range without hitting a block. */
		END
	}

	/**
	 * @param face the side of the block a {@link Kind#BLOCK} hit struck
	 */
	record Hit(Kind kind, Vec3 position, double distance, @Nullable LivingEntity entity, @Nullable Coin coin, boolean head, @Nullable Direction face) {
	}

	static List<Hit> cast(Level level, Entity shooter, Vec3 from, Vec3 direction, double range, List<Coin> coins) {
		Vec3 to = from.add(direction.scale(range));
		BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
		boolean blocked = block.getType() != HitResult.Type.MISS;
		Vec3 end = blocked ? block.getLocation() : to;

		List<Hit> hits = new ArrayList<>();
		for (Entity entity : level.getEntities(shooter, new AABB(from, end).inflate(1), e -> e instanceof LivingEntity living && living.isAlive() && !e.isSpectator())) {
			AABB box = entity.getBoundingBox().inflate(entity.getPickRadius());
			Optional<Vec3> clip = box.contains(from) ? Optional.of(from) : box.clip(from, end);
			clip.ifPresent(point -> hits.add(new Hit(Kind.ENTITY, point, point.distanceTo(from), (LivingEntity)entity, null, isHead(entity, point), null)));
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
				hits.add(new Hit(Kind.COIN, nearest, along, null, coin, false, null));
			}
		}
		hits.sort(Comparator.comparingDouble(Hit::distance));
		hits.add(new Hit(blocked ? Kind.BLOCK : Kind.END, end, from.distanceTo(end), null, null, false, blocked ? block.getDirection() : null));
		return hits;
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
