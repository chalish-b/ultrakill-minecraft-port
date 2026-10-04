package dev.ultracraft.client.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * A ParticleSystem on a viewmodel, simulated in its own space so its particles move with the gun: a steady rate
 * out of a rectangle, each particle a still sprite facing the camera that fades over its life. Its AnimatedTexture
 * swaps the texture of all of them between an array's slices every so often. Its scaling mode is Local, so only
 * its own scale (1) counts, not the bones above it.
 */
final class ViewmodelParticles {
	/** ParticleSystemShapeType.Rectangle: a 1 by 1 square in the shape's XY plane, before its scale. */
	private static final int RECTANGLE = 18;

	private record Particle(Vector3f position, float roll, double born, double size, double lifetime) {
	}

	final int bone;
	private final JsonObject settings;
	private final List<Identifier> frames = new ArrayList<>();
	private final double frameDelay;
	private final boolean randomFrame;
	private final RandomSource random = RandomSource.create();
	private final List<Particle> particles = new ArrayList<>();
	private double age;
	private double rateDue;
	private double sinceFrame;
	private int frame;

	ViewmodelParticles(JsonObject settings) {
		this.settings = settings;
		this.bone = settings.get("bone").getAsInt();
		JsonObject animated = settings.has("animatedTexture") ? settings.getAsJsonObject("animatedTexture") : null;
		if (animated != null) {
			for (JsonElement frame : animated.getAsJsonArray("frames")) {
				this.frames.add(Identifier.parse(frame.getAsString()));
			}
			this.frameDelay = animated.get("delay").getAsDouble();
			this.randomFrame = animated.get("randomFrame").getAsBoolean();
		} else {
			JsonArray textures = settings.getAsJsonArray("textures");
			if (!textures.isEmpty() && !textures.get(0).isJsonNull()) {
				this.frames.add(Identifier.parse(textures.get(0).getAsString()));
			}
			this.frameDelay = Double.POSITIVE_INFINITY;
			this.randomFrame = false;
		}
	}

	/** Its object is turned off: its particles are gone, and it starts over when it's turned back on. */
	void stop() {
		this.particles.clear();
		this.age = 0;
		this.rateDue = 0;
	}

	void update(double dt) {
		if (this.age < this.settings.get("duration").getAsDouble() || this.settings.get("looping").getAsBoolean()) {
			this.rateDue += constant(this.settings.getAsJsonObject("rate")) * dt;
			while (this.rateDue >= 1) {
				this.rateDue -= 1;
				emit();
			}
		}
		this.age += dt;
		this.particles.removeIf(particle -> this.age - particle.born > particle.lifetime);
		this.sinceFrame += dt;
		if (this.sinceFrame > this.frameDelay && !this.frames.isEmpty()) {
			this.sinceFrame = 0;
			this.frame = this.randomFrame ? this.random.nextInt(this.frames.size()) : (this.frame + 1) % this.frames.size();
		}
	}

	private void emit() {
		Vector3f position = new Vector3f();
		JsonObject shape = this.settings.has("shape") && !this.settings.get("shape").isJsonNull() ? this.settings.getAsJsonObject("shape") : null;
		if (shape != null && shape.get("type").getAsInt() == RECTANGLE) {
			JsonArray scale = shape.getAsJsonArray("scale");
			JsonArray rotation = shape.getAsJsonArray("rotation");
			JsonArray offset = shape.getAsJsonArray("position");
			position.set((this.random.nextFloat() - 0.5F) * scale.get(0).getAsFloat(), (this.random.nextFloat() - 0.5F) * scale.get(1).getAsFloat(), 0);
			// Quaternion.Euler: about z, then x, then y.
			new Quaternionf()
				.rotateY((float)Math.toRadians(rotation.get(1).getAsDouble()))
				.rotateX((float)Math.toRadians(rotation.get(0).getAsDouble()))
				.rotateZ((float)Math.toRadians(rotation.get(2).getAsDouble()))
				.transform(position);
			position.add(offset.get(0).getAsFloat(), offset.get(1).getAsFloat(), offset.get(2).getAsFloat());
		}
		this.particles.add(new Particle(position, (float)start(this.settings.getAsJsonObject("rotation")), this.age,
			start(this.settings.getAsJsonObject("size")), start(this.settings.getAsJsonObject("lifetime"))));
	}

	private static double constant(JsonObject curve) {
		return curve.has("constant") ? curve.get("constant").getAsDouble() : curve.has("max") ? curve.get("max").getAsDouble() : curve.get("scalar").getAsDouble();
	}

	/** A MinMaxCurve's value for a new particle: its constant, or random between its two. */
	private double start(JsonObject curve) {
		if (curve.has("min")) {
			double min = curve.get("min").getAsDouble();
			return min + this.random.nextDouble() * (curve.get("max").getAsDouble() - min);
		}
		return constant(curve);
	}

	/**
	 * Draws the particles about the system's bone ({@code bone}, a viewmodel bone matrix in camera space), each
	 * tinted by {@code startColor} and its color over its life.
	 */
	void submit(PoseStack poseStack, SubmitNodeCollector collector, Matrix4f bone, float[] startColor) {
		if (this.particles.isEmpty() || this.frames.isEmpty()) {
			return;
		}
		Vector3f origin = bone.getTranslation(new Vector3f());
		Vector3f x = bone.transformDirection(new Vector3f(1, 0, 0)).normalize();
		Vector3f y = bone.transformDirection(new Vector3f(0, 1, 0)).normalize();
		Vector3f z = bone.transformDirection(new Vector3f(0, 0, 1)).normalize();
		JsonObject overLife = this.settings.has("colorOverLifetime") ? this.settings.getAsJsonObject("colorOverLifetime") : null;
		collector.submitCustomGeometry(poseStack, RenderTypes.beaconBeam(this.frames.get(this.frame), true), (pose, buffer) -> {
			for (Particle particle : this.particles) {
				double life = (this.age - particle.born) / particle.lifetime;
				float[] tint = overLife != null && overLife.has("gradient") ? gradient(overLife.getAsJsonObject("gradient"), life) : new float[]{1, 1, 1, 1};
				float red = startColor[0] * tint[0];
				float green = startColor[1] * tint[1];
				float blue = startColor[2] * tint[2];
				float alpha = startColor[3] * tint[3];
				Vector3f p = particle.position;
				Vector3f center = new Vector3f(origin).add(new Vector3f(x).mul(p.x)).add(new Vector3f(y).mul(p.y)).add(new Vector3f(z).mul(p.z));
				float half = (float)(particle.size / 2);
				Quaternionf roll = new Quaternionf().rotationZ(particle.roll);
				float[][] corners = {{-half, -half, 0, 1}, {half, -half, 1, 1}, {half, half, 1, 0}, {-half, half, 0, 0}};
				// Both faces, so the winding doesn't matter.
				for (int pass = 0; pass < 2; pass++) {
					for (int i = 0; i < 4; i++) {
						float[] c = corners[pass == 0 ? i : 3 - i];
						Vector3f corner = roll.transform(new Vector3f(c[0], c[1], 0)).add(center);
						buffer.addVertex(pose, corner.x, corner.y, corner.z).setColor(red, green, blue, alpha).setUv(c[2], c[3])
							.setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 0, 1);
					}
				}
			}
		});
	}

	/** An extracted Gradient's color at {@code t}: its keys joined straight. */
	private static float[] gradient(JsonObject gradient, double t) {
		float[] rgb = keyed(gradient.getAsJsonArray("colors"), t, 3);
		float[] alpha = keyed(gradient.getAsJsonArray("alphas"), t, 1);
		return new float[]{rgb[0], rgb[1], rgb[2], alpha[0]};
	}

	private static float[] keyed(JsonArray keys, double t, int width) {
		JsonArray previous = keys.get(0).getAsJsonArray();
		for (JsonElement element : keys) {
			JsonArray key = element.getAsJsonArray();
			double t1 = key.get(0).getAsDouble();
			if (t <= t1) {
				double t0 = previous.get(0).getAsDouble();
				double f = t1 > t0 ? Math.clamp((t - t0) / (t1 - t0), 0, 1) : 1;
				float[] out = new float[width];
				for (int c = 0; c < width; c++) {
					double a = previous.get(c + 1).getAsDouble();
					out[c] = (float)(a + f * (key.get(c + 1).getAsDouble() - a));
				}
				return out;
			}
			previous = key;
		}
		float[] out = new float[width];
		for (int c = 0; c < width; c++) {
			out[c] = previous.get(c + 1).getAsFloat();
		}
		return out;
	}
}
