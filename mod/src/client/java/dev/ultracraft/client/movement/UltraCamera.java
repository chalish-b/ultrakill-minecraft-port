package dev.ultracraft.client.movement;

import dev.ultracraft.client.UltraConfig;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/** ULTRAKILL's CameraController: crouch height, walk bob, screen shake, strafe tilt, dash FOV and zoom. */
final class UltraCamera {
	/** The camera's height above the player's root, in units (Player/Main Camera). */
	static final double ORIGINAL_HEIGHT = 1.4;
	private static final double SLIDE_DROP = 0.625;

	/** cc.defaultPos.y: eases towards {@link #defaultTarget}. */
	double defaultPos = ORIGINAL_HEIGHT;
	double defaultTarget = ORIGINAL_HEIGHT;
	/** transform.localPosition.y: {@link #defaultPos} plus the walk bob. */
	private double localPos = ORIGINAL_HEIGHT;
	private double bobTarget = ORIGINAL_HEIGHT - 0.2;
	private double cameraShaking;
	/** Shake offset this frame, in units along the camera's right and up. */
	private double shakeRight;
	private double shakeUp;
	/** Roll in degrees; positive leans left. */
	private double tilt;
	private final double[] tiltVelocity = new double[1];
	/** 0: dashing or sliding forwards, 1: backwards, 2: any other direction. */
	int dodgeDirection;
	/** Field of view relative to the player's setting. */
	private double fovRatio = 1;
	/** CameraController.Zoom: the share of the player's field of view the view narrows to, or 0 while not zooming. */
	private double zoom;
	/** The view is narrowed by a zoom, or still widening back from one. */
	private boolean zoomed;
	private final RandomSource random = RandomSource.create();

	/** CameraController.StopShake. */
	void stopShake() {
		this.cameraShaking = 0;
		this.shakeRight = 0;
		this.shakeUp = 0;
	}

	void shake(double amount) {
		amount *= UltraConfig.get().screenShake;
		if (amount != 0 && this.cameraShaking < amount) {
			this.cameraShaking = amount;
		}
	}

	/** Weapons widen the view for a moment when they fire (cam.fieldOfView += defaultFov / 40). */
	void kickFov(double amount) {
		this.fovRatio += amount;
	}

	/** CameraController.Zoom while {@code share} is above 0, StopZoom at 0. */
	void zoom(double share) {
		this.zoom = share;
		if (share > 0) {
			this.zoomed = true;
		}
	}

	/** How much slower the view turns: while zooming, by how far it has narrowed. */
	double lookScale() {
		return this.zoom > 0 ? this.fovRatio : 1;
	}

	void crouch() {
		this.defaultTarget = ORIGINAL_HEIGHT - SLIDE_DROP;
	}

	void resetToDefaultPos() {
		this.localPos = this.defaultPos;
		this.bobTarget = this.defaultPos - 0.1;
	}

	/** The player's root rose by {@code rise} units without the camera moving; the camera then eases up. */
	void holdWorldHeight(double rise) {
		this.localPos -= rise;
		this.defaultPos = this.localPos;
	}

	static int dodgeIndex(Vec3 dodgeDirection, Vec3 forward) {
		if (U.eq(dodgeDirection, forward)) {
			return 0;
		}
		return U.eq(dodgeDirection, forward.scale(-1)) ? 1 : 2;
	}

	/** CameraController.Update's shake. It runs on real time, so it plays out during a hitstop too. */
	void updateShake(double dt) {
		if (this.cameraShaking > 0) {
			if (this.cameraShaking > 1) {
				this.shakeRight = this.random.nextInt(3) - 1;
				this.shakeUp = this.random.nextInt(3) - 1;
			} else {
				this.shakeRight = this.cameraShaking * (this.random.nextDouble() * 2 - 1);
				this.shakeUp = this.cameraShaking * (this.random.nextDouble() * 2 - 1);
			}
			this.cameraShaking -= dt * 3;
		} else {
			this.shakeRight = 0;
			this.shakeUp = 0;
		}
	}

	void lateUpdate(double dt, UltraMovement movement, double defaultFov) {
		UltraConfig config = UltraConfig.get();

		if (dt > 0) {
			this.tilt = U.smoothDampAngle(this.tilt, 0, this.tiltVelocity, 0.5, dt);
		}
		double current = this.tilt > 180 ? this.tilt - 360 : this.tilt;
		double target = -movement.movementHor;
		if (config.cameraTilt) {
			this.tilt = movement.boost
				? U.moveTowards(current, target * 5, dt * 100 * (Math.abs(current - target * 5) + 0.01))
				: U.moveTowards(current, target, dt * 25 * (Math.abs(current - target) + 0.01));
		} else {
			this.tilt = U.moveTowards(current, 0, dt * 25 * (Math.abs(current) + 0.01));
		}

		if (this.zoom > 0) {
			this.fovRatio = U.moveTowards(this.fovRatio, this.zoom, dt * 300 / defaultFov);
		} else if (movement.boost) {
			if (this.dodgeDirection == 0) {
				this.fovRatio = 1 - 1 / 20.0;
			} else if (this.dodgeDirection == 1) {
				this.fovRatio = 1 + 1 / 10.0;
			}
		} else {
			this.fovRatio = U.moveTowards(this.fovRatio, 1, dt * 300 / defaultFov);
		}
		if (this.zoom == 0 && this.fovRatio >= 1) {
			this.zoomed = false;
		}

		if (this.defaultPos != this.defaultTarget) {
			this.defaultPos = U.moveTowards(this.defaultPos, this.defaultTarget, (Math.abs(this.defaultTarget - this.defaultPos) + 0.5) * dt * 10);
		}

		if (this.cameraShaking > 0) {
			this.localPos = this.defaultPos;
			return;
		}
		if (movement.walking && movement.standing && this.defaultPos == this.defaultTarget) {
			double speed = Math.min(movement.body.velocity.length(), 15) / 15;
			this.localPos = U.moveTowards(this.localPos, this.bobTarget, dt * 0.5 * speed);
			if (this.localPos == this.bobTarget && this.bobTarget != this.defaultPos) {
				this.bobTarget = this.defaultPos;
			} else if (this.localPos == this.bobTarget) {
				this.bobTarget = this.defaultPos - 0.1;
			}
		} else {
			this.localPos = this.defaultPos;
			this.bobTarget = this.defaultPos - 0.1;
		}
	}

	/** Camera height above the player's root, in units. */
	double height() {
		return this.localPos;
	}

	double shakeRight() {
		return this.shakeRight;
	}

	double shakeUp() {
		return this.shakeUp;
	}

	double roll() {
		return this.tilt;
	}

	double fovMultiplier() {
		return UltraConfig.get().fovEffects || this.zoomed ? this.fovRatio : 1;
	}
}
