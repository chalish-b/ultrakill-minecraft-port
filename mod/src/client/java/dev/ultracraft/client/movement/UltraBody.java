package dev.ultracraft.client.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

/**
 * The ULTRAKILL player's Rigidbody: velocity in ULTRAKILL units per second, Unity's force modes, and one
 * physics step that integrates gravity and forces and then moves the player through Minecraft's
 * collision. Contacts remove the velocity going into what was hit, as PhysX does for a frictionless body.
 */
final class UltraBody {
	/** ULTRAKILL's Time.fixedDeltaTime: physics runs at 125 Hz. */
	static final double FIXED_DT = 0.008;
	static final double MASS = 100;
	/** Physics.gravity is (0, -40, 0). */
	static final double GRAVITY = 40;
	static final Vec3 GRAVITY_DIRECTION = new Vec3(0, -1, 0);
	static final Vec3 UP = new Vec3(0, 1, 0);

	Vec3 velocity = Vec3.ZERO;
	boolean useGravity = true;
	/** Falls are capped at this speed (units a second) once gravity and forces are in. */
	double maxFallSpeed = Double.POSITIVE_INFINITY;
	private Vec3 pendingVelocityChange = Vec3.ZERO;

	/** ForceMode.Force: applied over the next physics step. */
	void addForce(Vec3 force) {
		this.pendingVelocityChange = this.pendingVelocityChange.add(force.scale(FIXED_DT / MASS));
	}

	/** ForceMode.Impulse. */
	void addImpulse(Vec3 impulse) {
		this.pendingVelocityChange = this.pendingVelocityChange.add(impulse.scale(1 / MASS));
	}

	/** ForceMode.VelocityChange. */
	void addVelocityChange(Vec3 change) {
		this.pendingVelocityChange = this.pendingVelocityChange.add(change);
	}

	/** ForceMode.Acceleration. */
	void addAcceleration(Vec3 acceleration) {
		this.pendingVelocityChange = this.pendingVelocityChange.add(acceleration.scale(FIXED_DT));
	}

	/** Stopped where it is: no velocity, and nothing still to be added to it. */
	void stop() {
		this.velocity = Vec3.ZERO;
		this.pendingVelocityChange = Vec3.ZERO;
	}

	static Vec3 gravityVector() {
		return GRAVITY_DIRECTION.scale(GRAVITY);
	}

	/**
	 * Runs one physics step. {@code blocksPerUnit} converts the step's displacement to blocks.
	 *
	 * @return what the step collided with
	 */
	Contact step(LocalPlayer player, double blocksPerUnit) {
		if (this.useGravity) {
			this.velocity = this.velocity.add(gravityVector().scale(FIXED_DT));
		}
		this.velocity = this.velocity.add(this.pendingVelocityChange);
		this.pendingVelocityChange = Vec3.ZERO;
		if (this.velocity.y < -this.maxFallSpeed) {
			this.velocity = new Vec3(this.velocity.x, -this.maxFallSpeed, this.velocity.z);
		}

		Vec3 beforeContact = this.velocity;
		Vec3 wanted = this.velocity.scale(FIXED_DT * blocksPerUnit);
		Vec3 start = player.position();
		player.move(MoverType.SELF, wanted);
		Vec3 moved = player.position().subtract(start);

		boolean hitX = Math.abs(moved.x - wanted.x) > 1.0E-7;
		boolean hitZ = Math.abs(moved.z - wanted.z) > 1.0E-7;
		boolean hitBelow = wanted.y < 0 && moved.y > wanted.y + 1.0E-7;
		boolean hitAbove = wanted.y > 0 && moved.y < wanted.y - 1.0E-7;
		double vx = hitX ? 0 : this.velocity.x;
		double vz = hitZ ? 0 : this.velocity.z;
		double vy = this.velocity.y;
		if (hitBelow && vy < 0 || hitAbove && vy > 0) {
			vy = 0;
		}
		this.velocity = new Vec3(vx, vy, vz);
		return new Contact(beforeContact, wanted, hitX, hitZ, hitBelow);
	}

	/**
	 * @param beforeContact velocity before the contacts removed anything
	 * @param attempted the displacement the step tried, in blocks
	 */
	record Contact(Vec3 beforeContact, Vec3 attempted, boolean hitX, boolean hitZ, boolean hitBelow) {
		boolean hitHorizontally() {
			return this.hitX || this.hitZ;
		}
	}
}
