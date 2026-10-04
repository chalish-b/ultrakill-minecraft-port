package dev.ultracraft.client.combat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.OptionalDouble;

/**
 * A BoxCollider in the world: its centre, its own axes and half its size along each, all in blocks. It turns
 * with an object that looks along {@code forward} with the world's up (Transform.LookAt).
 */
record OrientedBox(Vec3 center, Vec3 right, Vec3 up, Vec3 forward, double halfRight, double halfUp, double halfForward) {
	private static final Vec3 WORLD_UP = new Vec3(0, 1, 0);

	/**
	 * The box of an object at {@code origin} looking along {@code forward}.
	 *
	 * @param box an extracted BoxCollider: its centre and size in the object's space
	 * @param scale blocks per unit of the object's space
	 */
	static OrientedBox of(Vec3 origin, Vec3 forward, JsonObject box, double scale) {
		Vec3 f = forward.lengthSqr() > 0 ? forward.normalize() : new Vec3(0, 0, 1);
		Vec3 r = WORLD_UP.cross(f);
		r = r.lengthSqr() > 1.0E-8 ? r.normalize() : new Vec3(1, 0, 0);
		Vec3 u = f.cross(r).normalize();
		JsonArray c = box.getAsJsonArray("center");
		JsonArray s = box.getAsJsonArray("size");
		Vec3 center = origin.add(r.scale(c.get(0).getAsDouble() * scale)).add(u.scale(c.get(1).getAsDouble() * scale)).add(f.scale(c.get(2).getAsDouble() * scale));
		return new OrientedBox(center, r, u, f, s.get(0).getAsDouble() * scale / 2, s.get(1).getAsDouble() * scale / 2, s.get(2).getAsDouble() * scale / 2);
	}

	OrientedBox moved(Vec3 by) {
		return new OrientedBox(this.center.add(by), this.right, this.up, this.forward, this.halfRight, this.halfUp, this.halfForward);
	}

	OrientedBox inflated(double by) {
		return new OrientedBox(this.center, this.right, this.up, this.forward, this.halfRight + by, this.halfUp + by, this.halfForward + by);
	}

	/** How far the box reaches along {@code direction} (a unit vector) from {@code origin}. */
	double reach(Vec3 origin, Vec3 direction) {
		return this.center.subtract(origin).dot(direction) + radius(direction);
	}

	/** Half the box's extent along a unit vector. */
	private double radius(Vec3 axis) {
		return Math.abs(this.right.dot(axis)) * this.halfRight + Math.abs(this.up.dot(axis)) * this.halfUp + Math.abs(this.forward.dot(axis)) * this.halfForward;
	}

	/** Whether the box overlaps an axis-aligned one (separating axes). */
	boolean intersects(AABB box) {
		Vec3 half = new Vec3(box.getXsize() / 2, box.getYsize() / 2, box.getZsize() / 2);
		Vec3 between = box.getCenter().subtract(this.center);
		Vec3[] own = {this.right, this.up, this.forward};
		Vec3[] world = {new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1)};
		for (Vec3 axis : world) {
			if (separates(axis, between, half)) {
				return false;
			}
		}
		for (Vec3 axis : own) {
			if (separates(axis, between, half)) {
				return false;
			}
		}
		for (Vec3 a : own) {
			for (Vec3 b : world) {
				Vec3 axis = a.cross(b);
				if (axis.lengthSqr() > 1.0E-10 && separates(axis.normalize(), between, half)) {
					return false;
				}
			}
		}
		return true;
	}

	private boolean separates(Vec3 axis, Vec3 between, Vec3 half) {
		double other = Math.abs(axis.x) * half.x + Math.abs(axis.y) * half.y + Math.abs(axis.z) * half.z;
		return Math.abs(between.dot(axis)) > radius(axis) + other;
	}

	/** How far from a point the box is (0 inside it). */
	double distanceTo(Vec3 point) {
		Vec3 d = point.subtract(this.center);
		double x = Math.max(Math.abs(d.dot(this.right)) - this.halfRight, 0);
		double y = Math.max(Math.abs(d.dot(this.up)) - this.halfUp, 0);
		double z = Math.max(Math.abs(d.dot(this.forward)) - this.halfForward, 0);
		return Math.sqrt(x * x + y * y + z * z);
	}

	/** How far along a ray from {@code from} (along the unit {@code direction}) it enters the box, within {@code reach}. */
	OptionalDouble clip(Vec3 from, Vec3 direction, double reach) {
		Vec3 d = from.subtract(this.center);
		double near = 0;
		double far = reach;
		Vec3[] axes = {this.right, this.up, this.forward};
		double[] halves = {this.halfRight, this.halfUp, this.halfForward};
		for (int i = 0; i < 3; i++) {
			double start = d.dot(axes[i]);
			double speed = direction.dot(axes[i]);
			if (Math.abs(speed) < 1.0E-10) {
				if (Math.abs(start) > halves[i]) {
					return OptionalDouble.empty();
				}
				continue;
			}
			double t1 = (-halves[i] - start) / speed;
			double t2 = (halves[i] - start) / speed;
			near = Math.max(near, Math.min(t1, t2));
			far = Math.min(far, Math.max(t1, t2));
			if (near > far) {
				return OptionalDouble.empty();
			}
		}
		return OptionalDouble.of(near);
	}
}
