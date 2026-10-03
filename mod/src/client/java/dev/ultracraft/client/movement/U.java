package dev.ultracraft.client.movement;

import net.minecraft.world.phys.Vec3;

/** Unity's Mathf and Vector3 functions with Unity's exact semantics, for code ported from ULTRAKILL. */
final class U {
	private static final double EPSILON_NORMAL_SQRT = 1.0E-15;
	private static final double EQUALITY_SQR = 9.99999944E-11;

	private U() {
	}

	/** Mathf.Clamp, argument order and all: a call with min above max returns min whenever value is below it. */
	static double clamp(double value, double min, double max) {
		if (value < min) {
			value = min;
		} else if (value > max) {
			value = max;
		}
		return value;
	}

	static double clamp01(double value) {
		return clamp(value, 0, 1);
	}

	static double lerp(double a, double b, double t) {
		return a + (b - a) * clamp01(t);
	}

	static double inverseLerp(double a, double b, double value) {
		return a != b ? clamp01((value - a) / (b - a)) : 0;
	}

	static double moveTowards(double current, double target, double maxDelta) {
		if (Math.abs(target - current) <= maxDelta) {
			return target;
		}
		return current + Math.signum(target - current) * maxDelta;
	}

	static double repeat(double t, double length) {
		return clamp(t - Math.floor(t / length) * length, 0, length);
	}

	static double deltaAngle(double current, double target) {
		double delta = repeat(target - current, 360);
		if (delta > 180) {
			delta -= 360;
		}
		return delta;
	}

	/** Mathf.SmoothDamp. {@code velocity[0]} is the by-reference velocity. */
	static double smoothDamp(double current, double target, double[] velocity, double smoothTime, double deltaTime) {
		smoothTime = Math.max(0.0001, smoothTime);
		double omega = 2 / smoothTime;
		double x = omega * deltaTime;
		double exp = 1 / (1 + x + 0.48 * x * x + 0.235 * x * x * x);
		double change = current - target;
		double originalTo = target;
		target = current - change;
		double temp = (velocity[0] + omega * change) * deltaTime;
		velocity[0] = (velocity[0] - omega * temp) * exp;
		double output = target + (change + temp) * exp;
		if (originalTo - current > 0 == output > originalTo) {
			output = originalTo;
			velocity[0] = (output - originalTo) / deltaTime;
		}
		return output;
	}

	static double smoothDampAngle(double current, double target, double[] velocity, double smoothTime, double deltaTime) {
		target = current + deltaAngle(current, target);
		return smoothDamp(current, target, velocity, smoothTime, deltaTime);
	}

	static Vec3 normalized(Vec3 v) {
		double length = v.length();
		return length > 1.0E-5 ? v.scale(1 / length) : Vec3.ZERO;
	}

	static boolean eq(Vec3 a, Vec3 b) {
		return a.subtract(b).lengthSqr() < EQUALITY_SQR;
	}

	static boolean isZero(Vec3 v) {
		return eq(v, Vec3.ZERO);
	}

	static Vec3 projectOnPlane(Vec3 vector, Vec3 planeNormal) {
		double sqrMag = planeNormal.dot(planeNormal);
		if (sqrMag < Double.MIN_NORMAL) {
			return vector;
		}
		return vector.subtract(planeNormal.scale(vector.dot(planeNormal) / sqrMag));
	}

	static Vec3 project(Vec3 vector, Vec3 onNormal) {
		double sqrMag = onNormal.dot(onNormal);
		if (sqrMag < Double.MIN_NORMAL) {
			return Vec3.ZERO;
		}
		return onNormal.scale(vector.dot(onNormal) / sqrMag);
	}

	static Vec3 clampMagnitude(Vec3 vector, double maxLength) {
		double sqrMag = vector.lengthSqr();
		if (sqrMag > maxLength * maxLength) {
			return vector.scale(maxLength / Math.sqrt(sqrMag));
		}
		return vector;
	}

	static Vec3 lerp(Vec3 a, Vec3 b, double t) {
		t = clamp01(t);
		return a.add(b.subtract(a).scale(t));
	}

	static Vec3 moveTowards(Vec3 current, Vec3 target, double maxDistanceDelta) {
		Vec3 toVector = target.subtract(current);
		double sqDist = toVector.lengthSqr();
		if (sqDist == 0 || maxDistanceDelta >= 0 && sqDist <= maxDistanceDelta * maxDistanceDelta) {
			return target;
		}
		return current.add(toVector.scale(maxDistanceDelta / Math.sqrt(sqDist)));
	}

	/** Vector3.Angle, in degrees. */
	static double angle(Vec3 from, Vec3 to) {
		double denominator = Math.sqrt(from.lengthSqr() * to.lengthSqr());
		if (denominator < EPSILON_NORMAL_SQRT) {
			return 0;
		}
		double dot = clamp(from.dot(to) / denominator, -1, 1);
		return Math.toDegrees(Math.acos(dot));
	}

	static Vec3 reflect(Vec3 inDirection, Vec3 inNormal) {
		return inNormal.scale(-2 * inNormal.dot(inDirection)).add(inDirection);
	}
}
