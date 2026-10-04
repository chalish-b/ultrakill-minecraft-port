package dev.ultracraft.client.combat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ultracraft.PlayerShape;
import dev.ultracraft.client.render.UltraModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * The power slam's shockwave (PhysicalShockwave): a flat disc growing out from the player's feet that sends every
 * enemy it reaches into the air, harder the longer the slam was. It does no damage, and never hurts the player.
 * Once it's at its full size it stops hitting and fades.
 */
final class Shockwave {
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	/** GroundCheck multiplies the shockwave's force by the slam's force and this. */
	private static final double SLAM_FORCE_MULTIPLIER = 2.25;
	/** PhysicalShockwave.Start has its look fade (ScaleNFade) at a tenth of its speed, in alpha a second. */
	private static final double FADE_SPEED_SHARE = 0.1;

	interface Hits {
		/** DeliverDamage's force up, as a speed in units a second. */
		void shockwaveEnemy(LivingEntity enemy, Vec3 push);
	}

	final UltraModel model;
	private final JsonObject settings;
	/** Blocks. */
	final Vec3 origin;
	private final double push;
	/** The disc's width scale, growing at its speed (its other scale stays the prefab's). */
	private double scale;
	private boolean fading;
	private double fadeTime;
	private final Set<Integer> hit = new HashSet<>();

	Shockwave(UltraModel model, Vec3 origin, double slamForce) {
		this.model = model;
		this.settings = model.script("physicalShockwave");
		this.origin = origin;
		this.scale = model.rest().sx(0);
		double force = this.settings.get("force").getAsDouble() * slamForce * SLAM_FORCE_MULTIPLIER;
		// DeliverDamage's force (up × force × 2) comes to a thousandth of it as speed.
		this.push = force * 2 / 1000;
	}

	void update(double dt, Level level, LocalPlayer player, Hits hits) {
		double speed = this.settings.get("speed").getAsDouble();
		this.scale += speed * dt;
		if (this.fading) {
			this.fadeTime += dt;
			return;
		}
		if (this.scale > this.settings.get("maxSize").getAsDouble()) {
			this.fading = true;
			return;
		}
		JsonArray extent = this.settings.getAsJsonArray("colliderExtent");
		double radius = this.scale * extent.get(0).getAsDouble() * S;
		double halfHeight = this.model.rest().sy(0) * extent.get(1).getAsDouble() * S;
		AABB area = new AABB(this.origin, this.origin).inflate(radius, halfHeight, radius);
		for (Entity entity : level.getEntities(player, area, e -> e instanceof LivingEntity living && living.isAlive() && !e.isSpectator())) {
			AABB box = entity.getBoundingBox();
			double x = Math.clamp(this.origin.x, box.minX, box.maxX) - this.origin.x;
			double z = Math.clamp(this.origin.z, box.minZ, box.maxZ) - this.origin.z;
			if (x * x + z * z <= radius * radius && this.hit.add(entity.getId())) {
				hits.shockwaveEnemy((LivingEntity)entity, new Vec3(0, this.push, 0));
			}
		}
	}

	/** How wide the disc is now, as the prefab's width scale. */
	double scale() {
		return this.scale;
	}

	/** How much alpha its look has lost since it started fading. */
	double faded() {
		return this.fadeTime * this.settings.get("speed").getAsDouble() * FADE_SPEED_SHARE;
	}

	/** Gone once its look has faded out completely. */
	boolean done() {
		return this.fading && faded() >= 1;
	}
}
