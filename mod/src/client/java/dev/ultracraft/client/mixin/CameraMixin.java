package dev.ultracraft.client.mixin;

import dev.ultracraft.client.UltraController;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Puts the first-person camera where ULTRAKILL's camera is, with its tilt and dash field of view. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	@Final
	private static Vector3fc FORWARDS;
	@Shadow
	@Final
	private static Vector3fc UP;
	@Shadow
	@Final
	private static Vector3fc LEFT;
	@Shadow
	private @Nullable Entity entity;
	@Shadow
	private float xRot;
	@Shadow
	private float yRot;
	@Shadow
	@Final
	private Quaternionf rotation;
	@Shadow
	@Final
	private Vector3f forwards;
	@Shadow
	@Final
	private Vector3f up;
	@Shadow
	@Final
	private Vector3f left;
	@Shadow
	private int matrixPropertiesDirty;
	@Shadow
	private boolean detached;

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Inject(method = "alignWithEntity", at = @At("HEAD"), cancellable = true)
	private void ultracraft$ultraCamera(float partialTicks, CallbackInfo ci) {
		if (this.entity == null || !UltraController.INSTANCE.isDriving(this.entity)) {
			return;
		}
		Vec3 position = UltraController.INSTANCE.cameraPosition();
		if (position == null) {
			return;
		}
		this.xRot = this.entity.getViewXRot(partialTicks);
		this.yRot = this.entity.getViewYRot(partialTicks);
		float roll = (float)Math.toRadians(UltraController.INSTANCE.cameraRoll());
		this.rotation.rotationYXZ((float)Math.PI - this.yRot * (float)(Math.PI / 180.0), -this.xRot * (float)(Math.PI / 180.0), roll);
		FORWARDS.rotate(this.rotation, this.forwards);
		UP.rotate(this.rotation, this.up);
		LEFT.rotate(this.rotation, this.left);
		this.matrixPropertiesDirty |= 3;
		this.setPosition(position);
		this.detached = false;
		ci.cancel();
	}

	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void ultracraft$dashFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
		if (this.entity != null && UltraController.INSTANCE.isDriving(this.entity)) {
			cir.setReturnValue(cir.getReturnValue() * UltraController.INSTANCE.fovMultiplier());
		}
	}
}
