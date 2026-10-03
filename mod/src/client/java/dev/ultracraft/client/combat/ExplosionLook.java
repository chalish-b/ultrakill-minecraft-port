package dev.ultracraft.client.combat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ultracraft.PlayerShape;
import dev.ultracraft.client.UltraConfig;
import dev.ultracraft.client.render.UltraModel;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The standard explosion ("Explosion") as it's drawn: its blast sphere with a glow that grows with it, its wider
 * push wave, a shockwave ring, a puff of smoke, a column of smoke rising for five seconds, a burst of sparks and a
 * spray of chunks trailing smoke. Its light isn't drawn.
 */
final class ExplosionLook {
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	/** The prefab's scale. */
	private static final double ROOT_SCALE = 12;
	/** The push wave's sphere (ExplosionShockwave): its color and glow, scaled by its texture's alpha. */
	private static final float WAVE_COLOR = 0.056603774F;
	private static final float WAVE_EMISSION = 0.16037736F;
	private static final float WAVE_TEXTURE_ALPHA = 125 / 255F;
	/** The Glow sprite on the blast sphere: its scale on the sphere, fading at 1 a second. */
	private static final double GLOW_SCALE = 1.75;
	/** The Shockwave sprite, lying flat: it grows by 50 scale a second and fades at 2.5. */
	private static final double RING_GROWTH = 50;
	private static final double RING_FADE = 2.5;
	/** Particle System (1): a puff of 30 smoke clouds thrown out at 10 units a second, growing to 20 units over their second. */
	private static final int PUFF_COUNT = 30;
	private static final double PUFF_SPEED = 10;
	private static final double PUFF_SIZE = 20;
	private static final double PUFF_LIFETIME = 1;
	private static final float PUFF_GRAY = 0.425F;
	private static final float PUFF_ALPHA = 0.49F;
	/** Particle System (2): ten 5-unit clouds a second for five seconds, rising at 5 units a second within 5 degrees of up, each lasting five seconds. */
	private static final double COLUMN_RATE = 10;
	private static final double COLUMN_DURATION = 5;
	private static final double COLUMN_SPEED = 5;
	private static final double COLUMN_SIZE = 5;
	private static final double COLUMN_LIFETIME = 5;
	private static final double COLUMN_ANGLE = 5;
	private static final float COLUMN_GRAY = 0.689F;
	private static final float COLUMN_ALPHA = 0.471F;
	/** Particle System: 50 sparks at 200 units a second, 0.5 to 2 units across, each trailing a white streak. */
	private static final int SPARK_COUNT = 50;
	private static final double SPARK_SPEED = 200;
	private static final double SPARK_LIFETIME = 0.175;
	/** The explosion's chunks (ExplosionChunk): 24 to 29 of them, flung at up to 250 units a second. */
	private static final double CHUNK_SPEED = 250;
	private static final double CHUNK_LIFETIME = 3;
	/** Once a chunk hits a wall it stops trailing smoke and is gone a second later. */
	private static final double CHUNK_LANDED_LIFETIME = 1;
	private static final double CHUNK_GRAVITY = 40;
	/** A chunk's smoke trail: 1 unit wide, lasting half a second, faint and additive. */
	private static final double CHUNK_TRAIL_TIME = 0.5;
	private static final float CHUNK_TRAIL_ALPHA = 0.471F * 0.502F;
	/** The prefab is removed after this long (its RemoveOnTime). */
	private static final double LIFETIME = 10;
	/** Its smoke is drawn with this: Legacy Particles/Multiply (Double) has no counterpart, so smoke is laid over. */
	private static final Identifier SMOKE = Identifier.fromNamespaceAndPath("ultracraft", "textures/viewmodel/smokesprite.png");
	private static @Nullable RenderType smokeTrail;

	private record Cloud(Vec3 start, Vec3 velocity, double born) {
	}

	private record Spark(Vec3 start, Vec3 direction, double size) {
	}

	private static final class Chunk {
		Vec3 position;
		Vec3 velocity;
		final Quaternionf rotation;
		final List<Vec3> trail = new ArrayList<>();
		final List<Double> trailTimes = new ArrayList<>();
		double landedAt = -1;

		Chunk(Vec3 position, Vec3 velocity, Quaternionf rotation) {
			this.position = position;
			this.velocity = velocity;
			this.rotation = rotation;
		}
	}

	final Explosion blast;
	final Explosion wave;
	private final Vec3 origin;
	private final Quaternionf rotation;
	private final RandomSource random = RandomSource.create();
	private final List<Cloud> puff = new ArrayList<>();
	private final List<Cloud> column = new ArrayList<>();
	private final List<Spark> sparks = new ArrayList<>();
	private final List<Chunk> chunks = new ArrayList<>();
	private double columnDue;
	private double age;

	ExplosionLook(Explosion blast, Explosion wave) {
		this.blast = blast;
		this.wave = wave;
		this.origin = blast.origin;
		this.rotation = blast.rotation;
		for (int i = 0; i < PUFF_COUNT; i++) {
			Vec3 point = insideUnitSphere();
			this.puff.add(new Cloud(point, direction(point).scale(PUFF_SPEED), 0));
		}
		for (int i = 0; i < SPARK_COUNT; i++) {
			Vec3 point = insideUnitSphere();
			this.sparks.add(new Spark(point, direction(point), 0.5 + this.random.nextDouble() * 1.5));
		}
		int chunks = 24 + this.random.nextInt(6);
		for (int i = 0; i < chunks; i++) {
			Vec3 offset = new Vec3(this.random.nextDouble() * 2 - 1, this.random.nextDouble() * 2 - 1, this.random.nextDouble() * 2 - 1);
			Vec3 velocity = new Vec3(this.random.nextDouble() * 2 - 1, this.random.nextDouble() * 3 - 1, this.random.nextDouble() * 2 - 1).scale(CHUNK_SPEED);
			Quaternionf spin = new Quaternionf().rotateXYZ(this.random.nextFloat() * 6.2831855F, this.random.nextFloat() * 6.2831855F, this.random.nextFloat() * 6.2831855F);
			this.chunks.add(new Chunk(this.origin.add(offset.scale(S)), velocity, spin));
		}
	}

	private Vec3 insideUnitSphere() {
		Vec3 point;
		do {
			point = new Vec3(this.random.nextDouble() * 2 - 1, this.random.nextDouble() * 2 - 1, this.random.nextDouble() * 2 - 1);
		} while (point.lengthSqr() > 1 || point.lengthSqr() == 0);
		return point;
	}

	private static Vec3 direction(Vec3 point) {
		return point.normalize();
	}

	boolean done() {
		return this.age > LIFETIME;
	}

	void update(double dt, Level level, Entity player) {
		this.age += dt;
		if (this.age < COLUMN_DURATION) {
			this.columnDue += COLUMN_RATE * dt;
			while (this.columnDue >= 1) {
				this.columnDue -= 1;
				// The column's cone points up from the prefab: within 5 degrees of its axis, from a 1 unit disc.
				double angle = this.random.nextDouble() * Math.PI * 2;
				double spread = Math.toRadians(COLUMN_ANGLE) * Math.sqrt(this.random.nextDouble());
				double radius = Math.sqrt(this.random.nextDouble());
				Vector3f start = new Vector3f((float)(Math.cos(angle) * radius), 0, (float)(Math.sin(angle) * radius));
				Vector3f up = new Vector3f((float)(Math.cos(angle) * Math.sin(spread)), (float)Math.cos(spread), (float)(Math.sin(angle) * Math.sin(spread)));
				this.rotation.transform(start);
				this.rotation.transform(up);
				this.column.add(new Cloud(new Vec3(start.x, start.y, start.z), new Vec3(up.x, up.y, up.z).scale(COLUMN_SPEED), this.age));
			}
		}
		this.column.removeIf(cloud -> this.age - cloud.born > COLUMN_LIFETIME);
		double scale = dt * S * UltraConfig.get().speedScale;
		for (Chunk chunk : this.chunks) {
			if (chunk.landedAt >= 0) {
				continue;
			}
			chunk.velocity = chunk.velocity.add(0, -CHUNK_GRAVITY * dt, 0);
			Vec3 next = chunk.position.add(chunk.velocity.scale(scale));
			HitResult hit = level.clip(new ClipContext(chunk.position, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
			if (hit.getType() != HitResult.Type.MISS) {
				chunk.position = hit.getLocation();
				chunk.landedAt = this.age;
			} else {
				chunk.position = next;
			}
			chunk.trail.addFirst(chunk.position);
			chunk.trailTimes.addFirst(this.age);
		}
		for (Chunk chunk : this.chunks) {
			while (!chunk.trailTimes.isEmpty() && this.age - chunk.trailTimes.getLast() > CHUNK_TRAIL_TIME) {
				chunk.trailTimes.removeLast();
				chunk.trail.removeLast();
			}
		}
		this.chunks.removeIf(chunk -> this.age > CHUNK_LIFETIME || (chunk.landedAt >= 0 && this.age - chunk.landedAt > CHUNK_LANDED_LIFETIME));
	}

	void render(LevelRenderContext context, Vec3 camera) {
		UltraModel model = UltraModel.get("explosion");
		if (model == null) {
			return;
		}
		PoseStack poseStack = context.poseStack();
		float units = (float)S;
		poseStack.pushPose();
		poseStack.translate(this.origin.x - camera.x, this.origin.y - camera.y, this.origin.z - camera.z);
		poseStack.rotate(this.rotation);
		if (!this.blast.removed) {
			poseStack.pushPose();
			float scale = (float)this.blast.meshScale() * units;
			poseStack.scale(scale, scale, scale);
			// The "Explosion" material adds its texture, fading.
			Effects.sphere(context, model.mesh(0), this.blast, camera, 1, 0);
			poseStack.popPose();
		}
		if (!this.wave.removed) {
			poseStack.pushPose();
			float scale = (float)this.wave.meshScale() * units;
			poseStack.scale(scale, scale, scale);
			Effects.sphere(context, model.mesh(1), this.wave, camera, WAVE_COLOR * WAVE_TEXTURE_ALPHA, WAVE_EMISSION * WAVE_TEXTURE_ALPHA);
			poseStack.popPose();
		}
		for (UltraModel.Sprite sprite : model.sprites()) {
			if (sprite.name().equals("Shockwave")) {
				double alpha = 1 - RING_FADE * this.age;
				if (alpha > 0) {
					poseStack.pushPose();
					poseStack.rotate(new Quaternionf().rotationX((float)Math.toRadians(-90)));
					float scale = (float)(ROOT_SCALE * (1 + RING_GROWTH * this.age)) * units;
					poseStack.scale(scale, scale, scale);
					Effects.quad(context, sprite.texture(), sprite.rect(), 1, 1, 1, (float)alpha);
					poseStack.popPose();
				}
			}
		}
		poseStack.popPose();
		for (UltraModel.Sprite sprite : model.sprites()) {
			if (sprite.name().equals("Glow") && !this.blast.removed) {
				double alpha = 1 - this.age;
				if (alpha > 0) {
					poseStack.pushPose();
					poseStack.translate(this.origin.x - camera.x, this.origin.y - camera.y, this.origin.z - camera.z);
					poseStack.rotate(Minecraft.getInstance().gameRenderer.mainCamera().rotation());
					float scale = (float)(this.blast.meshScale() * GLOW_SCALE) * units;
					poseStack.scale(scale, scale, scale);
					Effects.quad(context, sprite.texture(), sprite.rect(), 1, 1, 1, (float)alpha);
					poseStack.popPose();
				}
			}
		}
		renderClouds(context, camera);
		renderSparks(context, camera);
		renderChunks(context, camera);
	}

	private void renderClouds(LevelRenderContext context, Vec3 camera) {
		Quaternionf facing = Minecraft.getInstance().gameRenderer.mainCamera().rotation();
		if (this.age < PUFF_LIFETIME) {
			double life = this.age / PUFF_LIFETIME;
			float size = (float)(PUFF_SIZE * life * S);
			for (Cloud cloud : this.puff) {
				Vec3 at = this.origin.add(cloud.start.add(cloud.velocity.scale(this.age)).scale(S));
				billboard(context, camera, facing, at, size, PUFF_GRAY * 2, (float)(PUFF_ALPHA * (1 - life)));
			}
		}
		for (Cloud cloud : this.column) {
			double t = this.age - cloud.born;
			Vec3 at = this.origin.add(cloud.start.add(cloud.velocity.scale(t)).scale(S));
			billboard(context, camera, facing, at, (float)(COLUMN_SIZE * S), COLUMN_GRAY * 2, (float)(COLUMN_ALPHA * (1 - t / COLUMN_LIFETIME)));
		}
	}

	private static void billboard(LevelRenderContext context, Vec3 camera, Quaternionf facing, Vec3 at, float size, float gray, float alpha) {
		if (alpha <= 0 || size <= 0) {
			return;
		}
		PoseStack poseStack = context.poseStack();
		poseStack.pushPose();
		poseStack.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
		poseStack.rotate(facing);
		poseStack.scale(size, size, size);
		float g = Math.min(gray, 1);
		Effects.quad(context, SMOKE, new float[]{-0.5F, -0.5F, 0.5F, 0.5F}, g, g, g, alpha);
		poseStack.popPose();
	}

	/** Each spark's white streak back to where it started, narrowing and fading towards the start. */
	private void renderSparks(LevelRenderContext context, Vec3 camera) {
		if (this.age >= SPARK_LIFETIME) {
			return;
		}
		double t = this.age;
		float tailAlpha = (float)(1 - t / SPARK_LIFETIME);
		context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.debugQuads(), (pose, buffer) -> {
			for (Spark spark : this.sparks) {
				Vec3 start = this.origin.add(spark.start.scale(S)).subtract(camera);
				Vec3 head = start.add(spark.direction.scale(SPARK_SPEED * t * S));
				Vec3 side = spark.direction.cross(head).normalize().scale(spark.size * S / 2);
				if (!Double.isFinite(side.x)) {
					continue;
				}
				vertex(pose, buffer, head.add(side), 1);
				vertex(pose, buffer, head.subtract(side), 1);
				vertex(pose, buffer, start, tailAlpha);
				vertex(pose, buffer, start, tailAlpha);
			}
		});
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, Vec3 at, float alpha) {
		buffer.addVertex(pose, new Vector3f((float)at.x, (float)at.y, (float)at.z)).setColor(1, 1, 1, alpha);
	}

	private void renderChunks(LevelRenderContext context, Vec3 camera) {
		UltraModel model = UltraModel.get("explosion_chunk");
		Minecraft minecraft = Minecraft.getInstance();
		PoseStack poseStack = context.poseStack();
		for (Chunk chunk : this.chunks) {
			if (model != null && minecraft.level != null) {
				poseStack.pushPose();
				poseStack.translate(chunk.position.x - camera.x, chunk.position.y - camera.y, chunk.position.z - camera.z);
				poseStack.rotate(chunk.rotation);
				poseStack.scale((float)S, (float)S, (float)S);
				model.submit(poseStack, context.submitNodeCollector(), LightCoordsUtil.getLightCoords(minecraft.level, BlockPos.containing(chunk.position)));
				poseStack.popPose();
			}
			if (chunk.trail.size() < 2) {
				continue;
			}
			if (smokeTrail == null) {
				smokeTrail = RenderTypes.energySwirl(SMOKE, 0, 0);
			}
			context.submitNodeCollector().submitCustomGeometry(poseStack, smokeTrail, (pose, buffer) -> {
				for (int i = 0; i + 1 < chunk.trail.size(); i++) {
					Vec3 a = chunk.trail.get(i).subtract(camera);
					Vec3 b = chunk.trail.get(i + 1).subtract(camera);
					Vec3 side = b.subtract(a).cross(a).normalize().scale(S / 2);
					if (!Double.isFinite(side.x)) {
						continue;
					}
					float alphaA = (float)(CHUNK_TRAIL_ALPHA * (1 - (this.age - chunk.trailTimes.get(i)) / CHUNK_TRAIL_TIME));
					float alphaB = (float)(CHUNK_TRAIL_ALPHA * (1 - (this.age - chunk.trailTimes.get(i + 1)) / CHUNK_TRAIL_TIME));
					smoke(pose, buffer, a.add(side), 0, 0, alphaA);
					smoke(pose, buffer, a.subtract(side), 0, 1, alphaA);
					smoke(pose, buffer, b.subtract(side), 1, 1, alphaB);
					smoke(pose, buffer, b.add(side), 1, 0, alphaB);
				}
			});
		}
	}

	private static void smoke(PoseStack.Pose pose, VertexConsumer buffer, Vec3 at, float u, float v, float light) {
		float c = Math.max(light, 0);
		buffer.addVertex(pose, (float)at.x, (float)at.y, (float)at.z)
			.setColor(c, c, c, 1)
			.setUv(u, v)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(LightCoordsUtil.FULL_BRIGHT)
			.setNormal(pose, 0, 1, 0);
	}
}
