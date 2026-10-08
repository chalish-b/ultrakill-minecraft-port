package dev.ultracraft.client.combat;

import net.minecraft.world.phys.Vec3;

/**
 * One of the player's grenades (Grenade): a core the Core Eject throws, or a rocket. Revolver shots, coins and
 * railcannon beams set them off, and the Whiplash catches them, by the trigger sphere around each.
 */
abstract sealed class Grenade permits Core, Rocket {
	/** Blocks. */
	Vec3 position;
	boolean removed;
	/** On the Whiplash's hook, which carries it: it doesn't move or touch anything by itself. */
	boolean hooked;

	Grenade(Vec3 position) {
		this.position = position;
	}

	/** The middle of its trigger sphere. */
	abstract Vec3 hitCenter();

	/** Its trigger sphere's radius, in blocks. */
	abstract double hitRadius();

	/** Something sets its velocity, in units per second. */
	abstract void launch(Vec3 velocity);
}
