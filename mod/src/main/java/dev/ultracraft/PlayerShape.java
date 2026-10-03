package dev.ultracraft;

import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EntityAttachments;
import net.minecraft.world.entity.EntityDimensions;

/**
 * The ULTRAKILL player's collider, in Minecraft blocks.
 *
 * <p>ULTRAKILL's player is 3.5 units tall and Minecraft's is 1.8 blocks, so one unit is 1.8 / 3.5 blocks.
 * Sizes keep Minecraft's 0.6-block width so the player still fits through one-block gaps.
 */
public final class PlayerShape {
	public static final double BLOCKS_PER_UNIT = 1.8 / 3.5;

	/** Standing: 3.5 units tall, eyes 2.9 units above the feet. */
	public static final EntityDimensions STANDING = EntityDimensions.scalable(0.6F, 1.8F)
		.withEyeHeight((float)(2.9 * BLOCKS_PER_UNIT))
		.withAttachments(EntityAttachments.builder().attach(EntityAttachment.VEHICLE, Avatar.DEFAULT_VEHICLE_ATTACHMENT));

	/** Sliding or crouched: 1.25 units tall, eyes 1.15 units above the feet. */
	public static final EntityDimensions CROUCHED = EntityDimensions.scalable(0.6F, (float)(1.25 * BLOCKS_PER_UNIT))
		.withEyeHeight((float)(1.15 * BLOCKS_PER_UNIT));

	private PlayerShape() {
	}
}
