package dev.ultracraft.combat;

import dev.ultracraft.Ultracraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Phantom;

/** How the Whiplash treats what it catches. */
public final class WhiplashTargets {
	/**
	 * Enemies too heavy to pull: the Whiplash pulls the player to them instead (HookArm's enemies outside its
	 * lightEnemies).
	 */
	public static final TagKey<EntityType<?>> HEAVY = TagKey.create(Registries.ENTITY_TYPE, Ultracraft.id("whiplash_heavy"));

	private WhiplashTargets() {
	}

	public static boolean heavy(Entity entity) {
		return entity.is(HEAVY);
	}

	/**
	 * Whether the enemy flies rather than walks (has no GroundCheckEnemy): pulled, it comes to the player's camera
	 * instead of their body, and stops dead when it gets there.
	 */
	public static boolean flying(Entity entity) {
		return entity.isNoGravity()
			|| entity instanceof Mob mob && mob.getNavigation() instanceof FlyingPathNavigation
			|| entity instanceof Ghast || entity instanceof HappyGhast || entity instanceof Phantom || entity instanceof Bat || entity instanceof Blaze;
	}
}
