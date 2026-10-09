package dev.ultracraft.client.combat;

import dev.ultracraft.combat.BlockDamage;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The blocks a shot bores through and the one that stops it, nearest first, and where it ends: the Electric
 * railcannon's beam, and a cannonball meeting a wall. A stopping block's hit has the face it was struck on.
 */
record Tunnel(List<Hitscan.Hit> blocks, Vec3 end) {
	/**
	 * Bores along {@code direction} from {@code from}, up to {@code range} away: along its middle it goes through each
	 * block {@code damage} breaks, up to {@code depth} past the first block it meets, and stops at the next. Over that
	 * depth, every block within {@code radius} of its middle is hit too.
	 */
	static Tunnel bore(Level level, LocalPlayer player, Vec3 from, Vec3 direction, double range, float damage, double radius, double depth) {
		Vec3 to = from.add(direction.scale(range));
		Set<BlockPos> bored = new HashSet<>();
		List<Hitscan.Hit> blocks = new ArrayList<>();
		double first = -1;
		Vec3 end = to;
		while (true) {
			BlockHitResult hit = Hitscan.clip(level, player, from, to, bored);
			if (hit.getType() == HitResult.Type.MISS) {
				break;
			}
			double distance = hit.getLocation().distanceTo(from);
			if (first < 0) {
				first = distance;
			}
			BlockPos pos = hit.getBlockPos();
			if (distance - first <= depth && BlockDamage.breaksInOneHit(level, pos, damage)) {
				bored.add(pos);
				blocks.add(new Hitscan.Hit(Hitscan.Kind.BLOCK, hit.getLocation(), distance, null, null, null, false, null, pos));
				continue;
			}
			end = hit.getLocation();
			bored.add(pos);
			blocks.add(new Hitscan.Hit(Hitscan.Kind.BLOCK, end, distance, null, null, null, false, hit.getDirection(), pos));
			break;
		}
		if (first >= 0) {
			Vec3 start = from.add(direction.scale(first));
			Vec3 last = from.add(direction.scale(Math.min(end.distanceTo(from), first + depth)));
			AABB area = new AABB(start, last).inflate(radius);
			for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(area.minX, area.minY, area.minZ), BlockPos.containing(area.maxX, area.maxY, area.maxZ))) {
				if (bored.contains(pos)) {
					continue;
				}
				BlockState state = level.getBlockState(pos);
				if (state.isAir()) {
					continue;
				}
				VoxelShape shape = state.getCollisionShape(level, pos);
				if (shape.isEmpty()) {
					continue;
				}
				AABB box = shape.bounds().move(pos);
				if (ClientCombat.nearSegment(box, start, last, radius)) {
					Vec3 center = box.getCenter();
					blocks.add(new Hitscan.Hit(Hitscan.Kind.BLOCK, center, center.subtract(from).dot(direction), null, null, null, false, null, pos.immutable()));
				}
			}
		}
		return new Tunnel(blocks, end);
	}
}
