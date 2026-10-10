package dev.ultracraft.client.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ultracraft.client.render.animation.Pose;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * ULTRAKILL's RevolverCylinder: the cylinder turns one chamber per shot, clicking into place, and spins freely
 * while the piercer charges. It overrides the animated rotation of its bone, as the LateUpdate it ports does.
 */
final class RevolverCylinder {
	private final int bone;
	private final Vector3f axis;
	/** Degrees per second. */
	private final float speed;
	private final Quaternionf[] chambers;
	private final Quaternionf current;
	private int target;
	private boolean freeSpinning;

	RevolverCylinder(JsonObject json, Pose rest) {
		this.bone = json.get("bone").getAsInt();
		JsonArray axis = json.getAsJsonArray("rotationAxis");
		this.axis = new Vector3f(axis.get(0).getAsFloat(), axis.get(1).getAsFloat(), axis.get(2).getAsFloat());
		this.speed = json.get("speed").getAsFloat();
		int bullets = json.get("bulletAmount").getAsInt();
		this.current = new Quaternionf(rest.qx(this.bone), rest.qy(this.bone), rest.qz(this.bone), rest.qw(this.bone));
		this.chambers = new Quaternionf[bullets];
		for (int i = 0; i < bullets; i++) {
			this.chambers[i] = new Quaternionf(this.current).mul(euler(this.axis, 360 / bullets * i));
		}
	}

	int bone() {
		return this.bone;
	}

	/** DoTurn: on to the next chamber. */
	void turn() {
		this.target = (this.target + 1) % this.chambers.length;
	}

	/**
	 * Turns towards the target chamber, or spins with the charge, and writes the rotation into the pose.
	 *
	 * @param spinSpeed the piercer's charge, which spins the cylinder (RevolverCylinder.spinSpeed)
	 * @return whether the cylinder just clicked into place
	 */
	boolean update(double dt, double spinSpeed, Pose pose) {
		if (spinSpeed * 10 > this.speed) {
			this.freeSpinning = true;
			this.current.mul(euler(this.axis, (float)(dt * spinSpeed * 10)));
		} else if (this.freeSpinning) {
			this.freeSpinning = false;
			this.target = closest();
		}
		boolean clicked = false;
		Quaternionf target = this.chambers[this.target];
		if (!this.freeSpinning && angle(this.current, target) > 0.1F) {
			rotateTowards(this.current, target, (float)(dt * this.speed));
			if (angle(this.current, target) <= 0.1F) {
				this.current.set(target);
				clicked = true;
			}
		}
		pose.setRotation(this.bone, this.current.x, this.current.y, this.current.z, this.current.w);
		return clicked;
	}

	private int closest() {
		int result = 0;
		float best = angle(this.current, this.chambers[0]);
		for (int i = 1; i < this.chambers.length; i++) {
			float angle = angle(this.current, this.chambers[i]);
			if (angle < best) {
				best = angle;
				result = i;
			}
		}
		return result;
	}

	/** Quaternion.Euler for {@code axis * degrees}: Z, then X, then Y. */
	private static Quaternionf euler(Vector3f axis, float degrees) {
		return new Quaternionf().rotationYXZ(
			(float)Math.toRadians(axis.y * degrees), (float)Math.toRadians(axis.x * degrees), (float)Math.toRadians(axis.z * degrees)
		);
	}

	/** Quaternion.Angle, in degrees. */
	static float angle(Quaternionf a, Quaternionf b) {
		float dot = Math.min(Math.abs(a.dot(b)), 1);
		return (float)Math.toDegrees(Math.acos(dot) * 2);
	}

	/** Quaternion.RotateTowards: at most {@code maxDegrees} closer to {@code to}. */
	static void rotateTowards(Quaternionf from, Quaternionf to, float maxDegrees) {
		float angle = angle(from, to);
		if (angle == 0) {
			from.set(to);
			return;
		}
		from.slerp(to, Math.min(1, maxDegrees / angle));
	}
}
