package dev.ultracraft.client.render.animation;

/** Every bone's local position, rotation (quaternion x, y, z, w) and scale. */
public final class Pose {
	final float[] position;
	final float[] rotation;
	final float[] scale;

	public Pose(int bones) {
		this.position = new float[bones * 3];
		this.rotation = new float[bones * 4];
		this.scale = new float[bones * 3];
	}

	public int bones() {
		return this.scale.length / 3;
	}

	public void set(Pose other) {
		System.arraycopy(other.position, 0, this.position, 0, this.position.length);
		System.arraycopy(other.rotation, 0, this.rotation, 0, this.rotation.length);
		System.arraycopy(other.scale, 0, this.scale, 0, this.scale.length);
	}

	public Pose copy() {
		Pose copy = new Pose(bones());
		copy.set(this);
		return copy;
	}

	public void setBone(int bone, float[] position, float[] rotation, float[] scale) {
		System.arraycopy(position, 0, this.position, bone * 3, 3);
		System.arraycopy(rotation, 0, this.rotation, bone * 4, 4);
		System.arraycopy(scale, 0, this.scale, bone * 3, 3);
	}

	public void setScale(int bone, float x, float y, float z) {
		this.scale[bone * 3] = x;
		this.scale[bone * 3 + 1] = y;
		this.scale[bone * 3 + 2] = z;
	}

	public void setRotation(int bone, float x, float y, float z, float w) {
		this.rotation[bone * 4] = x;
		this.rotation[bone * 4 + 1] = y;
		this.rotation[bone * 4 + 2] = z;
		this.rotation[bone * 4 + 3] = w;
	}

	public float px(int bone) {
		return this.position[bone * 3];
	}

	public float py(int bone) {
		return this.position[bone * 3 + 1];
	}

	public float pz(int bone) {
		return this.position[bone * 3 + 2];
	}

	public float qx(int bone) {
		return this.rotation[bone * 4];
	}

	public float qy(int bone) {
		return this.rotation[bone * 4 + 1];
	}

	public float qz(int bone) {
		return this.rotation[bone * 4 + 2];
	}

	public float qw(int bone) {
		return this.rotation[bone * 4 + 3];
	}

	public float sx(int bone) {
		return this.scale[bone * 3];
	}

	public float sy(int bone) {
		return this.scale[bone * 3 + 1];
	}

	public float sz(int bone) {
		return this.scale[bone * 3 + 2];
	}

	/** Moves this pose {@code weight} of the way to {@code to}: positions and scales linearly, rotations by nlerp. */
	void blend(Pose to, float weight) {
		for (int i = 0; i < this.position.length; i++) {
			this.position[i] += (to.position[i] - this.position[i]) * weight;
			this.scale[i] += (to.scale[i] - this.scale[i]) * weight;
		}
		for (int b = 0; b < this.rotation.length; b += 4) {
			float dot = 0;
			for (int i = 0; i < 4; i++) {
				dot += this.rotation[b + i] * to.rotation[b + i];
			}
			float sign = dot < 0 ? -1 : 1;
			for (int i = 0; i < 4; i++) {
				this.rotation[b + i] += (sign * to.rotation[b + i] - this.rotation[b + i]) * weight;
			}
		}
		normalizeRotations();
	}

	void normalizeRotations() {
		for (int b = 0; b < this.rotation.length; b += 4) {
			float x = this.rotation[b], y = this.rotation[b + 1], z = this.rotation[b + 2], w = this.rotation[b + 3];
			float length = (float)Math.sqrt(x * x + y * y + z * z + w * w);
			if (length > 0) {
				for (int i = 0; i < 4; i++) {
					this.rotation[b + i] /= length;
				}
			}
		}
	}
}
