package dev.ultracraft.combat;

import dev.ultracraft.PlayerShape;
import dev.ultracraft.UltraPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Fresh blood that heals: each splatter heals the first ULTRAKILL player who stands in it during its first
 * quarter of a second (Bloodsplatter's trigger collider). Blood from an explosion is used up without healing a
 * player that an explosion has just hurt.
 */
public final class BloodZones {
	private static final int LIFETIME_TICKS = 5;
	private static final List<Zone> ZONES = new ArrayList<>();

	private BloodZones() {
	}

	public static void add(ServerLevel level, Vec3 position, Gore gore, boolean fromExplosion) {
		Zone zone = new Zone(level, position, gore.radius * PlayerShape.BLOCKS_PER_UNIT, gore.hp, fromExplosion);
		if (!zone.tryHeal()) {
			ZONES.add(zone);
		}
	}

	public static void tick(MinecraftServer server) {
		for (Iterator<Zone> it = ZONES.iterator(); it.hasNext(); ) {
			Zone zone = it.next();
			if (zone.tryHeal() || --zone.ticksLeft <= 0) {
				it.remove();
			}
		}
	}

	private static final class Zone {
		final ServerLevel level;
		final Vec3 position;
		final double radius;
		final float hp;
		final boolean fromExplosion;
		int ticksLeft = LIFETIME_TICKS;

		Zone(ServerLevel level, Vec3 position, double radius, float hp, boolean fromExplosion) {
			this.level = level;
			this.position = position;
			this.radius = radius;
			this.hp = hp;
			this.fromExplosion = fromExplosion;
		}

		boolean tryHeal() {
			for (ServerPlayer player : this.level.players()) {
				if (!((UltraPlayer)player).ultracraft$isActive() || !player.isAlive()) {
					continue;
				}
				if (distance(player.getBoundingBox(), this.position) <= this.radius) {
					if (!this.fromExplosion || !UltraHealth.exploded(player)) {
						UltraHealth.heal(player, this.hp);
					}
					return true;
				}
			}
			return false;
		}
	}

	private static double distance(AABB box, Vec3 point) {
		double dx = Math.max(Math.max(box.minX - point.x, 0), point.x - box.maxX);
		double dy = Math.max(Math.max(box.minY - point.y, 0), point.y - box.maxY);
		double dz = Math.max(Math.max(box.minZ - point.z, 0), point.z - box.maxZ);
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
