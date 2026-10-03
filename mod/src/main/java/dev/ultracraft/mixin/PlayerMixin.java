package dev.ultracraft.mixin;

import dev.ultracraft.UltraPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Stores the ULTRAKILL movement state and applies its rules: ULTRAKILL's pose, no fall damage, no hunger. */
@Mixin(Player.class)
public abstract class PlayerMixin implements UltraPlayer {
	@Unique
	private boolean ultracraft$active;
	@Unique
	private boolean ultracraft$crouched;

	@Override
	public boolean ultracraft$isActive() {
		return this.ultracraft$active;
	}

	@Override
	public boolean ultracraft$isCrouched() {
		return this.ultracraft$active && this.ultracraft$crouched;
	}

	@Override
	public void ultracraft$setUltraState(boolean active, boolean crouched) {
		if (active == this.ultracraft$active && crouched == this.ultracraft$crouched) {
			return;
		}
		this.ultracraft$active = active;
		this.ultracraft$crouched = crouched;
		Player self = (Player)(Object)this;
		if (active) {
			self.setPose(crouched ? Pose.SWIMMING : Pose.STANDING);
		}
		self.refreshDimensions();
	}

	@Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
	private void ultracraft$ultraPose(CallbackInfo ci) {
		if (this.ultracraft$active) {
			((Player)(Object)this).setPose(this.ultracraft$crouched ? Pose.SWIMMING : Pose.STANDING);
			ci.cancel();
		}
	}

	@Inject(method = "tick", at = @At("HEAD"))
	private void ultracraft$forgetFalls(CallbackInfo ci) {
		if (this.ultracraft$active) {
			((Player)(Object)this).resetFallDistance();
		}
	}

	@Inject(method = "causeFallDamage", at = @At("HEAD"), cancellable = true)
	private void ultracraft$noFallDamage(double fallDistance, float damageModifier, DamageSource damageSource, CallbackInfoReturnable<Boolean> cir) {
		if (this.ultracraft$active) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "causeFoodExhaustion", at = @At("HEAD"), cancellable = true)
	private void ultracraft$noHunger(float amount, CallbackInfo ci) {
		if (this.ultracraft$active) {
			ci.cancel();
		}
	}
}
