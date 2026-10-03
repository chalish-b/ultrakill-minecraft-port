package dev.ultracraft.client;

import dev.ultracraft.PlayerShape;
import dev.ultracraft.UltraPlayer;
import dev.ultracraft.client.movement.UltraInput;
import dev.ultracraft.client.movement.UltraMovement;
import dev.ultracraft.net.UltraStatePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Runs ULTRAKILL movement on the local player once per rendered frame, and hands the player back to
 * Minecraft's movement where ULTRAKILL's has no equivalent: deep water, lava, ladders, flight, riding.
 */
public final class UltraController {
	public static final UltraController INSTANCE = new UltraController();
	/** Water at least this deep (blocks) is swum through with Minecraft's movement. */
	private static final double SWIM_DEPTH = 0.6;

	private final UltraInput input = new UltraInput();
	private @Nullable UltraMovement movement;
	private @Nullable LocalPlayer driven;
	private long lastFrameNanos;
	private double clock;
	private Vec3 lastDeltaMovement = Vec3.ZERO;
	private @Nullable LocalPlayer syncedPlayer;
	private boolean syncedActive;
	private boolean syncedCrouched;

	private UltraController() {
	}

	/** Called every frame, after input and before rendering. */
	public void onFrame() {
		Minecraft minecraft = Minecraft.getInstance();
		long now = System.nanoTime();
		double dt = this.lastFrameNanos == 0 ? 0 : Math.min((now - this.lastFrameNanos) / 1.0E9, 0.1);
		this.lastFrameNanos = now;

		while (UltraKeys.TOGGLE.consumeClick()) {
			toggle(minecraft);
		}

		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			release();
			return;
		}
		if (!shouldDrive(player)) {
			release();
			sync(player);
			return;
		}
		if (this.movement == null || this.driven != player) {
			release();
			take(minecraft, player);
		}
		if (minecraft.isPaused()) {
			return;
		}

		this.clock += dt;
		this.movement.syncExternal(this.lastDeltaMovement);
		this.input.sample(minecraft.options);
		this.movement.frame(dt, this.clock, this.input);
		this.lastDeltaMovement = this.movement.deltaMovement();
		player.setDeltaMovement(this.lastDeltaMovement);
		if (player.isSprinting()) {
			player.setSprinting(false);
		}
		sync(player);
	}

	private boolean shouldDrive(LocalPlayer player) {
		return UltraConfig.get().enabled
			&& ClientPlayNetworking.canSend(UltraStatePayload.TYPE)
			&& player.isAlive()
			&& !player.isSpectator()
			&& !player.isPassenger()
			&& !player.isSleeping()
			&& !player.isFallFlying()
			&& !player.getAbilities().flying
			&& !player.onClimbable()
			&& !player.isInLava()
			&& player.getFluidHeight(FluidTags.WATER) < SWIM_DEPTH;
	}

	private void take(Minecraft minecraft, LocalPlayer player) {
		this.driven = player;
		this.movement = new UltraMovement(player);
		this.lastDeltaMovement = player.getDeltaMovement();
		this.input.discard(minecraft.options);
		player.setSprinting(false);
	}

	private void release() {
		if (this.movement != null) {
			this.movement.close();
			if (this.driven != null) {
				this.driven.setDeltaMovement(this.movement.deltaMovement());
			}
		}
		this.movement = null;
		this.driven = null;
	}

	/** Keeps the player's state on both sides in step with what drives it. */
	private void sync(LocalPlayer player) {
		boolean active = this.movement != null;
		boolean crouched = active && this.movement.shortCollider();
		((UltraPlayer)player).ultracraft$setUltraState(active, crouched);
		if (player != this.syncedPlayer || active != this.syncedActive || crouched != this.syncedCrouched) {
			if (ClientPlayNetworking.canSend(UltraStatePayload.TYPE)) {
				ClientPlayNetworking.send(new UltraStatePayload(active, crouched));
			}
			this.syncedPlayer = player;
			this.syncedActive = active;
			this.syncedCrouched = crouched;
		}
	}

	private void toggle(Minecraft minecraft) {
		UltraConfig config = UltraConfig.get();
		config.enabled = !config.enabled;
		config.save();
		if (minecraft.player != null) {
			minecraft.player.sendOverlayMessage(Component.translatable(config.enabled ? "ultracraft.toggle.on" : "ultracraft.toggle.off"));
		}
	}

	/** Whether ULTRAKILL movement is driving this entity right now. */
	public boolean isDriving(Object entity) {
		return this.movement != null && entity == this.driven;
	}

	public @Nullable UltraMovement movement() {
		return this.movement;
	}

	/** Step height for the driven player, in blocks. */
	public float stepHeight() {
		return UltraConfig.get().stepMode == UltraConfig.StepMode.ULTRAKILL ? (float)(0.5 * PlayerShape.BLOCKS_PER_UNIT) : 0.6F;
	}

	/** The first-person camera's position while ULTRAKILL movement drives the player, or null. */
	public @Nullable Vec3 cameraPosition() {
		return this.movement != null && Minecraft.getInstance().options.getCameraType().isFirstPerson() ? this.movement.eyePosition() : null;
	}

	public double cameraRoll() {
		return this.movement != null ? this.movement.cameraRoll() : 0;
	}

	public float fovMultiplier() {
		return this.movement != null ? (float)this.movement.fovMultiplier() : 1;
	}
}
