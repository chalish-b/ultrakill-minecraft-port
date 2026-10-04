package dev.ultracraft.client.combat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ultracraft.PlayerShape;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * One of a prefab's ParticleSystems, from its extracted settings: bursts and a steady rate over its duration, out
 * of a sphere or a cone, each particle flying straight (and falling with its gravity) as its size and color
 * change over its life. Particles are drawn as sprites facing the camera, and with a trail module as streaks
 * back to where they started. Its scaling mode is Local, so only its own scale (1) counts. Particles don't
 * collide.
 */
final class Particles {
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	/** Physics.gravity, units per second squared. */
	private static final double GRAVITY = 40;
	/** ParticleSystemShapeType's hemispheres. */
	private static final int HEMISPHERE = 2;
	private static final int HEMISPHERE_SHELL = 3;
	/** ParticleSystemShapeType.Circle. */
	private static final int CIRCLE = 10;

	/** How a particle material blends: Legacy Particles/Multiply (Double) has no counterpart, so it's laid over. */
	private enum Blend {
		OVER,
		ADDITIVE
	}

	private record Particle(Vec3 start, Vec3 velocity, double size, double lifetime, double born, float[] color, float roll) {
	}

	private final JsonObject settings;
	/** Blocks. */
	private final Vec3 origin;
	/** The system's rotation in the world, as Minecraft draws it (Unity's local axes, mirrored). */
	private final Quaternionf rotation;
	/** The system's rotation in Unity's axes, for its shape. */
	private final Quaternionf unityRotation;
	private final RandomSource random = RandomSource.create();
	private final List<Particle> particles = new ArrayList<>();
	private final @Nullable Identifier headTexture;
	private final @Nullable Blend headBlend;
	private @Nullable RenderType headType;
	private final float headColorScale;
	private final @Nullable Blend trailBlend;
	private final @Nullable Identifier trailTexture;
	private double age;
	private double rateDue;
	private int burstsDone;

	/**
	 * @param worldRotation the prefab's rotation, as Minecraft draws it
	 * @param unityRotation the system's rotation within the prefab, in Unity's axes
	 */
	Particles(JsonObject settings, Vec3 origin, Quaternionf worldRotation, Quaternionf unityRotation) {
		this.settings = settings;
		this.origin = origin;
		this.unityRotation = unityRotation;
		this.rotation = worldRotation;
		JsonArray textures = settings.getAsJsonArray("textures");
		JsonArray materials = settings.getAsJsonArray("materials");
		this.headTexture = textures.isEmpty() || textures.get(0).isJsonNull() || settings.get("renderMode").getAsInt() != 0
			? null : Identifier.parse(textures.get(0).getAsString());
		this.headBlend = this.headTexture == null ? null : blend(materials.get(0));
		this.headColorScale = this.headBlend == null ? 1 : multiplier(materials.get(0));
		this.trailBlend = settings.has("trail") && materials.size() > 1 ? blend(materials.get(1)) : null;
		this.trailTexture = this.trailBlend == null || textures.size() < 2 || textures.get(1).isJsonNull() ? null : Identifier.parse(textures.get(1).getAsString());
	}

	private static @Nullable Blend blend(JsonElement material) {
		if (material == null || material.isJsonNull()) {
			return null;
		}
		JsonObject floats = material.getAsJsonObject().getAsJsonObject("floats");
		double src = floats.has("_SrcBlend") ? floats.get("_SrcBlend").getAsDouble() : 1;
		double dst = floats.has("_DstBlend") ? floats.get("_DstBlend").getAsDouble() : 0;
		return src == 5 && dst == 1 || src == 1 && dst == 1 ? Blend.ADDITIVE : Blend.OVER;
	}

	/** Multiply (Double) doubles the particle's color. */
	private static float multiplier(JsonElement material) {
		JsonObject floats = material.getAsJsonObject().getAsJsonObject("floats");
		return floats.has("_Mode") && floats.get("_Mode").getAsDouble() == 6 ? 2 : 1;
	}

	boolean done() {
		double duration = this.settings.get("duration").getAsDouble();
		return this.age > duration && this.particles.isEmpty();
	}

	void update(double dt) {
		double duration = this.settings.get("duration").getAsDouble();
		JsonArray bursts = this.settings.getAsJsonArray("bursts");
		while (this.burstsDone < bursts.size() && bursts.get(this.burstsDone).getAsJsonArray().get(0).getAsDouble() <= this.age + dt) {
			int count = (int)Math.round(sample(bursts.get(this.burstsDone).getAsJsonArray().get(1).getAsJsonObject()));
			for (int i = 0; i < count; i++) {
				emit(this.age);
			}
			this.burstsDone++;
		}
		if (this.age < duration) {
			this.rateDue += sample(this.settings.getAsJsonObject("rate")) * dt;
			while (this.rateDue >= 1) {
				this.rateDue -= 1;
				emit(this.age);
			}
		}
		this.age += dt;
		this.particles.removeIf(particle -> this.age - particle.born > particle.lifetime);
	}

	/** A value of a MinMaxCurve at the start of a particle: its constant, or random between its two. */
	private double sample(JsonObject curve) {
		if (curve.has("constant")) {
			return curve.get("constant").getAsDouble();
		}
		if (curve.has("min")) {
			double min = curve.get("min").getAsDouble();
			return min + this.random.nextDouble() * (curve.get("max").getAsDouble() - min);
		}
		return curve.get("scalar").getAsDouble() * curve.getAsJsonArray("keys").get(0).getAsJsonArray().get(1).getAsDouble();
	}

	/** A curve over a particle's life, at {@code t} (0 to 1): its keys joined straight, times its scalar. */
	private static double evaluate(JsonObject curve, double t) {
		if (curve.has("constant")) {
			return curve.get("constant").getAsDouble();
		}
		JsonArray keys = curve.getAsJsonArray("keys");
		double scalar = curve.get("scalar").getAsDouble();
		JsonArray previous = keys.get(0).getAsJsonArray();
		if (t <= previous.get(0).getAsDouble()) {
			return scalar * previous.get(1).getAsDouble();
		}
		for (int i = 1; i < keys.size(); i++) {
			JsonArray key = keys.get(i).getAsJsonArray();
			double t0 = previous.get(0).getAsDouble();
			double t1 = key.get(0).getAsDouble();
			if (t <= t1) {
				double f = t1 > t0 ? (t - t0) / (t1 - t0) : 1;
				return scalar * (previous.get(1).getAsDouble() + f * (key.get(1).getAsDouble() - previous.get(1).getAsDouble()));
			}
			previous = key;
		}
		return scalar * previous.get(1).getAsDouble();
	}

	/** A MinMaxGradient's color at the start of a particle. */
	private float[] startColor(JsonObject color) {
		if (color.has("color")) {
			return floats(color.getAsJsonArray("color"));
		}
		if (color.has("min")) {
			float[] min = floats(color.getAsJsonArray("min"));
			float[] max = floats(color.getAsJsonArray("max"));
			float f = this.random.nextFloat();
			return new float[]{min[0] + f * (max[0] - min[0]), min[1] + f * (max[1] - min[1]), min[2] + f * (max[2] - min[2]), min[3] + f * (max[3] - min[3])};
		}
		return gradient(color.getAsJsonObject("gradient"), this.random.nextFloat());
	}

	/** A gradient's color at {@code t}: its color and alpha keys joined straight. */
	/** An extracted Gradient's color at {@code t}, 0 to 1. */
	static float[] gradient(JsonObject gradient, double t) {
		float[] rgb = keyed(gradient.getAsJsonArray("colors"), t, 3);
		float[] a = keyed(gradient.getAsJsonArray("alphas"), t, 1);
		return new float[]{rgb[0], rgb[1], rgb[2], a[0]};
	}

	private static float[] keyed(JsonArray keys, double t, int width) {
		JsonArray previous = keys.get(0).getAsJsonArray();
		if (t > previous.get(0).getAsDouble()) {
			for (int i = 1; i < keys.size(); i++) {
				JsonArray key = keys.get(i).getAsJsonArray();
				double t0 = previous.get(0).getAsDouble();
				double t1 = key.get(0).getAsDouble();
				if (t <= t1) {
					double f = t1 > t0 ? (t - t0) / (t1 - t0) : 1;
					float[] out = new float[width];
					for (int c = 0; c < width; c++) {
						double a = previous.get(c + 1).getAsDouble();
						out[c] = (float)(a + f * (key.get(c + 1).getAsDouble() - a));
					}
					return out;
				}
				previous = key;
			}
		}
		float[] out = new float[width];
		for (int c = 0; c < width; c++) {
			out[c] = previous.get(c + 1).getAsFloat();
		}
		return out;
	}

	private static float[] floats(JsonArray array) {
		float[] out = new float[array.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = array.get(i).getAsFloat();
		}
		return out;
	}

	/** One particle: placed and aimed by the shape (in the system's own axes), then turned into the world. */
	private void emit(double born) {
		Vector3f position = new Vector3f();
		Vector3f direction = new Vector3f(0, 0, 1);
		JsonObject shape = this.settings.has("shape") && !this.settings.get("shape").isJsonNull() ? this.settings.getAsJsonObject("shape") : null;
		if (shape != null) {
			double radius = shape.get("radius").getAsDouble();
			int type = shape.get("type").getAsInt();
			if (type == 4) {
				// Cone: from a point on its base disc, leaning out from the axis by up to its angle at the rim.
				double angle = this.random.nextDouble() * Math.PI * 2;
				double r = Math.sqrt(this.random.nextDouble());
				position.set((float)(Math.cos(angle) * r * radius), (float)(Math.sin(angle) * r * radius), 0);
				double lean = Math.toRadians(shape.get("angle").getAsDouble()) * r;
				direction.set((float)(Math.cos(angle) * Math.sin(lean)), (float)(Math.sin(angle) * Math.sin(lean)), (float)Math.cos(lean));
			} else if (type == CIRCLE) {
				// Circle: anywhere on its disc, flying straight out from its centre across it.
				double angle = this.random.nextDouble() * Math.PI * 2;
				double r = Math.sqrt(this.random.nextDouble());
				direction.set((float)Math.cos(angle), (float)Math.sin(angle), 0);
				position.set(direction).mul((float)(r * radius));
			} else {
				// Sphere: anywhere inside it, flying straight out; a hemisphere keeps the half ahead.
				Vector3f point;
				do {
					point = new Vector3f(this.random.nextFloat() * 2 - 1, this.random.nextFloat() * 2 - 1, this.random.nextFloat() * 2 - 1);
				} while (point.lengthSquared() > 1 || point.lengthSquared() == 0);
				if ((type == HEMISPHERE || type == HEMISPHERE_SHELL) && point.z < 0) {
					point.z = -point.z;
				}
				position.set(point).mul((float)radius);
				direction.set(point).normalize();
			}
		}
		this.unityRotation.transform(position);
		this.unityRotation.transform(direction);
		Vec3 start = world(position);
		Vec3 velocity = world(direction).scale(sample(this.settings.getAsJsonObject("speed")));
		this.particles.add(new Particle(start, velocity, sample(this.settings.getAsJsonObject("size")), sample(this.settings.getAsJsonObject("lifetime")),
			born, startColor(this.settings.getAsJsonObject("color")), (float)sample(this.settings.getAsJsonObject("rotation"))));
	}

	/** A vector in the prefab's Unity axes, in the world's (still in units). */
	private Vec3 world(Vector3f unity) {
		Vector3f v = this.rotation.transform(new Vector3f(unity.x, unity.y, -unity.z));
		return new Vec3(v.x, v.y, v.z);
	}

	private Vec3 position(Particle particle, double t) {
		double fall = 0.5 * GRAVITY * sample(this.settings.getAsJsonObject("gravity")) * t * t;
		return this.origin.add(particle.start.add(particle.velocity.scale(t)).add(0, -fall, 0).scale(S));
	}

	private float[] color(Particle particle, double life) {
		float[] color = particle.color.clone();
		if (this.settings.has("colorOverLifetime")) {
			float[] over = overLife(this.settings.getAsJsonObject("colorOverLifetime"), life);
			for (int c = 0; c < 4; c++) {
				color[c] *= over[c];
			}
		}
		return color;
	}

	private static float[] overLife(JsonObject color, double life) {
		if (color.has("gradient")) {
			return gradient(color.getAsJsonObject("gradient"), life);
		}
		return color.has("color") ? floats(color.getAsJsonArray("color")) : new float[]{1, 1, 1, 1};
	}

	void render(LevelRenderContext context, Vec3 camera) {
		if (this.particles.isEmpty()) {
			return;
		}
		if (this.headTexture != null && this.headBlend != null) {
			renderHeads(context, camera);
		}
		if (this.trailBlend != null) {
			renderTrails(context, camera);
		}
	}

	private void renderHeads(LevelRenderContext context, Vec3 camera) {
		Quaternionf facing = Minecraft.getInstance().gameRenderer.mainCamera().rotation();
		if (this.headType == null) {
			this.headType = this.headBlend == Blend.ADDITIVE ? RenderTypes.energySwirl(this.headTexture, 0, 0) : RenderTypes.text(this.headTexture);
		}
		RenderType type = this.headType;
		PoseStack poseStack = context.poseStack();
		for (Particle particle : this.particles) {
			double t = this.age - particle.born;
			double life = t / particle.lifetime;
			double size = particle.size;
			if (this.settings.has("sizeOverLifetime")) {
				size *= evaluate(this.settings.getAsJsonObject("sizeOverLifetime"), life);
			}
			float[] color = color(particle, life);
			if (size <= 0 || color[3] <= 0) {
				continue;
			}
			Vec3 at = position(particle, t);
			poseStack.pushPose();
			poseStack.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
			poseStack.rotate(facing);
			poseStack.rotate(new Quaternionf().rotationZ(particle.roll));
			float half = (float)(size * S / 2);
			float scale = this.headColorScale;
			float r = Math.min(color[0] * scale, 1);
			float g = Math.min(color[1] * scale, 1);
			float b = Math.min(color[2] * scale, 1);
			if (this.headBlend == Blend.ADDITIVE) {
				float a = color[3];
				Effects.quad(context, type, new float[]{-half, -half, half, half}, r * a, g * a, b * a, 1);
			} else {
				Effects.quad(context, type, new float[]{-half, -half, half, half}, r, g, b, color[3]);
			}
			poseStack.popPose();
		}
	}

	/**
	 * Each particle's trail (the Trails module): the path it took over the last part of its life (the trail's
	 * lifetime is a share of the particle's), its texture stretched along it. It's as wide as the particle is now,
	 * narrowing towards the tail as the width over the trail says, and colored by its points' age (and the
	 * particle's color, if it inherits it).
	 */
	private void renderTrails(LevelRenderContext context, Vec3 camera) {
		JsonObject trail = this.settings.getAsJsonObject("trail");
		JsonObject width = trail.getAsJsonObject("widthOverTrail");
		JsonObject color = trail.getAsJsonObject("colorOverLifetime");
		double share = evaluate(trail.getAsJsonObject("lifetime"), 0);
		boolean sizeAffectsWidth = trail.get("sizeAffectsWidth").getAsBoolean();
		boolean inherit = !trail.has("inheritParticleColor") || trail.get("inheritParticleColor").getAsBoolean();
		RenderType type;
		if (this.trailTexture != null) {
			type = this.trailBlend == Blend.ADDITIVE ? RenderTypes.energySwirl(this.trailTexture, 0, 0) : RenderTypes.text(this.trailTexture);
		} else {
			type = this.trailBlend == Blend.ADDITIVE ? RenderTypes.lightning() : RenderTypes.debugQuads();
		}
		context.submitNodeCollector().submitCustomGeometry(context.poseStack(), type, (pose, buffer) -> {
			for (Particle particle : this.particles) {
				double t = this.age - particle.born;
				double life = t / particle.lifetime;
				double span = share * particle.lifetime;
				double tailAt = Math.max(0, t - span);
				Vec3 head = position(particle, t).subtract(camera);
				Vec3 tail = position(particle, tailAt).subtract(camera);
				Vec3 along = head.subtract(tail);
				double size = particle.size;
				if (this.settings.has("sizeOverLifetime")) {
					size *= evaluate(this.settings.getAsJsonObject("sizeOverLifetime"), life);
				}
				double base = (sizeAffectsWidth ? size : 1) * S / 2;
				Vec3 side = along.cross(head).normalize();
				if (!Double.isFinite(side.x) || along.lengthSqr() == 0 || base <= 0) {
					continue;
				}
				Vec3 headSide = side.scale(base * evaluate(width, 0));
				Vec3 tailSide = side.scale(base * evaluate(width, 1));
				float[] tint = inherit ? color(particle, life) : new float[]{1, 1, 1, 1};
				float[] headColor = times(overLife(color, 0), tint);
				float[] tailColor = times(overLife(color, span > 0 ? (t - tailAt) / span : 1), tint);
				if (this.trailBlend == Blend.ADDITIVE && this.trailTexture != null) {
					headColor = premultiplied(headColor);
					tailColor = premultiplied(tailColor);
				}
				Vec3[] corners = {head.add(headSide), head.subtract(headSide), tail.subtract(tailSide), tail.add(tailSide)};
				float[][] colors = {headColor, headColor, tailColor, tailColor};
				float[][] uvs = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
				// Both faces: some of these types cull back faces, and a streak shows from either side.
				for (int i = 0; i < 4; i++) {
					trailVertex(pose, buffer, corners[i], colors[i], uvs[i]);
				}
				for (int i = 3; i >= 0; i--) {
					trailVertex(pose, buffer, corners[i], colors[i], uvs[i]);
				}
			}
		});
	}

	private static float[] times(float[] a, float[] b) {
		return new float[]{a[0] * b[0], a[1] * b[1], a[2] * b[2], a[3] * b[3]};
	}

	/** The textured additive type adds the color as it is, so its alpha goes into it. */
	private static float[] premultiplied(float[] color) {
		return new float[]{color[0] * color[3], color[1] * color[3], color[2] * color[3], 1};
	}

	private void trailVertex(PoseStack.Pose pose, VertexConsumer buffer, Vec3 at, float[] color, float[] uv) {
		buffer.addVertex(pose, new Vector3f((float)at.x, (float)at.y, (float)at.z)).setColor(color[0], color[1], color[2], color[3]).setUv(uv[0], uv[1])
			.setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 0, 1);
	}
}
