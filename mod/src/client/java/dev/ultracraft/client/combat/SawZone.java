package dev.ultracraft.client.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The revving chainsaw's HurtZone, a capsule in front of the Sawed-On: an enemy that comes into it takes a hit
 * straight away, and another every {@code cooldown} seconds (counted in physics steps) while it stays.
 */
final class SawZone {
	/** Time.fixedDeltaTime. */
	private static final double STEP = 0.008;
	/** Points along the capsule's length checked against an enemy's box. */
	private static final int SAMPLES = 8;

	interface Hits {
		void sawHit(LivingEntity enemy);
	}

	/** Seconds until each enemy inside is hit again. */
	private final Map<LivingEntity, Double> inside = new LinkedHashMap<>();
	private double accumulator;

	/**
	 * @param from one end of the capsule's core, blocks
	 * @param to the other end
	 * @param radius blocks
	 */
	void update(double dt, Level level, LocalPlayer player, Vec3 from, Vec3 to, double radius, double cooldown, Hits hits) {
		this.accumulator += dt;
		while (this.accumulator >= STEP) {
			this.accumulator -= STEP;
			for (Map.Entry<LivingEntity, Double> entry : this.inside.entrySet()) {
				double timer = entry.getValue() - STEP;
				if (timer <= 0 && entry.getKey().isAlive()) {
					hits.sawHit(entry.getKey());
					timer = cooldown;
				}
				entry.setValue(timer);
			}
		}
		AABB area = new AABB(from, to).inflate(radius);
		List<Entity> touching = level.getEntities(player, area, e -> e instanceof LivingEntity living && living.isAlive() && !e.isSpectator()
			&& touches(e.getBoundingBox(), from, to, radius));
		Set<LivingEntity> now = new HashSet<>();
		for (Entity entity : touching) {
			LivingEntity enemy = (LivingEntity)entity;
			now.add(enemy);
			if (!this.inside.containsKey(enemy)) {
				this.inside.put(enemy, cooldown);
				hits.sawHit(enemy);
			}
		}
		this.inside.keySet().removeIf(enemy -> !now.contains(enemy));
	}

	/** The zone is switched off: it forgets who was inside. */
	void clear() {
		this.inside.clear();
		this.accumulator = 0;
	}

	private static boolean touches(AABB box, Vec3 from, Vec3 to, double radius) {
		for (int i = 0; i <= SAMPLES; i++) {
			Vec3 point = from.lerp(to, i / (double)SAMPLES);
			double x = Math.clamp(point.x, box.minX, box.maxX) - point.x;
			double y = Math.clamp(point.y, box.minY, box.maxY) - point.y;
			double z = Math.clamp(point.z, box.minZ, box.maxZ) - point.z;
			if (x * x + y * y + z * z <= radius * radius) {
				return true;
			}
		}
		return false;
	}
}
