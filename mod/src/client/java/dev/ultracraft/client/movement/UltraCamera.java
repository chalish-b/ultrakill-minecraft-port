package dev.ultracraft.client.movement;

import dev.ultracraft.client.UltraConfig;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/** ULTRAKILL's CameraController: crouch height, walk bob, screen shake, strafe tilt and dash FOV. */
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
	private final RandomSource random = RandomSource.create();

	void shake(double amount) {
		amount *= UltraConfig.get().screenShake;
		if (amount != 0 && this.cameraShaking < amount) {
			this.cameraShaking = amount;
		}
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

	void lateUpdate(double dt, UltraMovement movement, double defaultFov) {
		UltraConfig config = UltraConfig.get();
		if (this.cameraShaking > 0) {
			if (this.cameraShaking > 1) {
				this.shakeRight = this.random.nextInt(3) - 1;
				this.shakeUp = this.random.nextInt(3) - 1;
			} else {
				this.shakeRight = this.cameraShaking * (this.random.nextDouble() * 2 - 1);
				this.shakeUp = this.cameraShaking * (this.random.nextDouble() * 2 - 1);
			}
			this.cameraShaking -= dt * 3;
		}

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

		if (movement.boost) {
			if (this.dodgeDirection == 0) {
				this.fovRatio = 1 - 1 / 20.0;
			} else if (this.dodgeDirection == 1) {
				this.fovRatio = 1 + 1 / 10.0;
			}
		} else {
			this.fovRatio = U.moveTowards(this.fovRatio, 1, dt * 300 / defaultFov);
		}

		if (this.defaultPos != this.defaultTarget) {
			this.defaultPos = U.moveTowards(this.defaultPos, this.defaultTarget, (Math.abs(this.defaultTarget - this.defaultPos) + 0.5) * dt * 10);
		}

		if (this.cameraShaking > 0) {
			this.localPos = this.defaultPos;
			return;
		}
		this.shakeRight = 0;
		this.shakeUp = 0;
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
		return UltraConfig.get().fovEffects ? this.fovRatio : 1;
	}
}
