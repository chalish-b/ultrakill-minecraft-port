package dev.ultracraft;

import dev.ultracraft.combat.UltraHealth;

/**
 * ULTRAKILL movement state of a player, implemented on {@link net.minecraft.world.entity.player.Player}.
 *
 * <p>The client sets it on its local player and sends it to the server, which keeps the same state on
 * its copy of the player. Both sides use it for the player's size, pose, fall damage, hunger and health.
 */
public interface UltraPlayer {
	/** Whether ULTRAKILL movement is driving this player right now. */
	boolean ultracraft$isActive();

	/** Whether the player's collider is the short one (sliding, or stuck crouching under a ceiling). */
	boolean ultracraft$isCrouched();

	void ultracraft$setUltraState(boolean active, boolean crouched);

	/** Hard damage, kept on the server. */
	UltraHealth.State ultracraft$health();
}
