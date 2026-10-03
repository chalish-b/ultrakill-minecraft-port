package dev.ultracraft.client.combat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ultracraft.PlayerShape;
import dev.ultracraft.client.UltraConfig;
import dev.ultracraft.client.render.UltraModel;
import dev.ultracraft.client.render.animation.Pose;
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
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An explosion prefab ("Explosion", "Explosion Super") as it's drawn, from its extracted model: each Explosion's
 * sphere, its sprites (shockwave rings and the glow, grown and faded by their ScaleNFade, the glow facing the
 * camera), its particle systems, and the chunks a full explosion throws, trailing smoke. Its lights aren't drawn.
 */
final class ExplosionLook {
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	/** ExplosionChunk: 24 to 29 of them, flung at up to 250 units a second, gone after 3 seconds. */
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
	private static final Identifier SMOKE = Identifier.fromNamespaceAndPath("ultracraft", "textures/viewmodel/smokesprite.png");
	private static @Nullable RenderType smokeTrail;

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

	private final UltraModel model;
	/** The prefab's Explosions, by bone. */
	private final Map<Integer, Explosion> explosions = new HashMap<>();
	private final Vec3 origin;
	private final Quaternionf rotation;
	private final RandomSource random = RandomSource.create();
	private final List<Particles> particles = new ArrayList<>();
	private final List<Chunk> chunks = new ArrayList<>();
	private double age;

	/**
	 * @param explosions the prefab's Explosions, in the order of its model's "explosions" scripts
	 */
	ExplosionLook(UltraModel model, List<Explosion> explosions, Vec3 origin, Quaternionf rotation) {
		this.model = model;
		this.origin = origin;
		this.rotation = rotation;
		for (int i = 0; i < explosions.size(); i++) {
			JsonObject entry = model.scripts("explosions").get(i).getAsJsonObject();
			this.explosions.put(entry.get("bone").getAsInt(), explosions.get(i));
			if (entry.get("chunks").getAsBoolean()) {
				throwChunks();
			}
		}
		for (JsonElement element : model.particles()) {
			JsonObject system = element.getAsJsonObject();
			this.particles.add(new Particles(system, origin, rotation, unityRotation(model, system.get("bone").getAsInt())));
		}
	}

	/** The bone's rotation within the prefab, in Unity's axes. */
	private static Quaternionf unityRotation(UltraModel model, int bone) {
		Quaternionf rotation = new Quaternionf();
		for (int b = bone; b > 0; b = model.parent(b)) {
			Pose rest = model.rest();
			rotation.premul(new Quaternionf(rest.qx(b), rest.qy(b), rest.qz(b), rest.qw(b)));
		}
		return rotation;
	}

	/** Explosion.Start's chunks: from random points within a unit of the centre, flung every way and rather upwards. */
	private void throwChunks() {
		int count = 24 + this.random.nextInt(6);
		for (int i = 0; i < count; i++) {
			Vec3 offset = new Vec3(this.random.nextDouble() * 2 - 1, this.random.nextDouble() * 2 - 1, this.random.nextDouble() * 2 - 1);
			Vec3 velocity = new Vec3(this.random.nextDouble() * 2 - 1, this.random.nextDouble() * 3 - 1, this.random.nextDouble() * 2 - 1).scale(CHUNK_SPEED);
			Quaternionf spin = new Quaternionf().rotateXYZ(this.random.nextFloat() * 6.2831855F, this.random.nextFloat() * 6.2831855F, this.random.nextFloat() * 6.2831855F);
			this.chunks.add(new Chunk(this.origin.add(offset.scale(S)), velocity, spin));
		}
	}

	boolean done() {
		return this.age > LIFETIME;
	}

	void update(double dt, Level level, Entity player) {
		this.age += dt;
		for (Particles system : this.particles) {
			system.update(dt);
		}
		double scale = dt * S * UltraConfig.get().speedScale;
		for (Chunk chunk : this.chunks) {
			if (chunk.landedAt < 0) {
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
			while (!chunk.trailTimes.isEmpty() && this.age - chunk.trailTimes.getLast() > CHUNK_TRAIL_TIME) {
				chunk.trailTimes.removeLast();
				chunk.trail.removeLast();
			}
		}
		this.chunks.removeIf(chunk -> this.age > CHUNK_LIFETIME || (chunk.landedAt >= 0 && this.age - chunk.landedAt > CHUNK_LANDED_LIFETIME));
	}

	void render(LevelRenderContext context, Vec3 camera) {
		PoseStack poseStack = context.poseStack();
		float units = (float)S;
		poseStack.pushPose();
		poseStack.translate(this.origin.x - camera.x, this.origin.y - camera.y, this.origin.z - camera.z);
		poseStack.rotate(this.rotation);
		for (int part = 0; part < this.model.meshCount(); part++) {
			Explosion explosion = this.explosions.get(this.model.meshBone(part));
			if (explosion == null || explosion.removed) {
				continue;
			}
			poseStack.pushPose();
			float scale = (float)explosion.meshScale() * units;
			poseStack.scale(scale, scale, scale);
			JsonObject material = this.model.meshMaterial(part);
			// The Standard particle shader adds its color and glow by its texture's alpha; ULTRAKILL's Master
			// shader adds its texture.
			float textureAlpha = material.get("textureAlpha").getAsFloat();
			boolean standard = material.getAsJsonArray("keywords").toString().contains("_EMISSION");
			float textured = standard ? color(material, "_Color") * textureAlpha : 1;
			float glow = standard ? color(material, "_EmissionColor") * textureAlpha : 0;
			Effects.sphere(context, this.model.mesh(part), explosion, camera, textured, glow);
			poseStack.popPose();
		}
		renderSprites(context, camera);
		poseStack.popPose();
		for (Particles system : this.particles) {
			system.render(context, camera);
		}
		renderChunks(context, camera);
	}

	private static float color(JsonObject material, String name) {
		JsonObject colors = material.getAsJsonObject("colors");
		return colors.has(name) ? colors.getAsJsonArray(name).get(0).getAsFloat() : 0;
	}

	/**
	 * Each sprite on its bone as the prefab now has it: Explosions grow their spheres (and what's on them), and
	 * ScaleNFade grows and fades its object. Sprites on a finished Explosion's sphere are gone with it.
	 */
	private void renderSprites(LevelRenderContext context, Vec3 camera) {
		Pose pose = this.model.rest().copy();
		Map<Integer, double[]> fades = new HashMap<>();
		for (JsonElement element : this.model.scripts("scaleNFade")) {
			JsonObject entry = element.getAsJsonObject();
			int bone = entry.get("bone").getAsInt();
			if (entry.get("scale").getAsInt() != 0) {
				float grow = (float)(entry.get("scaleSpeed").getAsDouble() * this.age);
				pose.setScale(bone, pose.sx(bone) + grow, pose.sy(bone) + grow, pose.sz(bone) + grow);
			}
			if (entry.get("fade").getAsInt() != 0 && entry.get("ft").getAsInt() == 0) {
				fades.put(bone, new double[]{entry.get("fadeSpeed").getAsDouble()});
			}
		}
		for (Map.Entry<Integer, Explosion> entry : this.explosions.entrySet()) {
			float scale = (float)entry.getValue().localScale();
			pose.setScale(entry.getKey(), scale, scale, scale);
		}
		Matrix4f[] world = this.model.boneMatrices(pose, new Quaternionf());
		PoseStack poseStack = context.poseStack();
		Quaternionf facing = new Quaternionf(this.rotation).conjugate().mul(Minecraft.getInstance().gameRenderer.mainCamera().rotation());
		for (UltraModel.Sprite sprite : this.model.sprites()) {
			int bone = sprite.bone();
			if (gone(bone)) {
				continue;
			}
			double[] fade = fades.get(bone);
			double alpha = sprite.color()[3] - (fade == null ? 0 : fade[0] * this.age);
			if (alpha <= 0) {
				continue;
			}
			poseStack.pushPose();
			poseStack.scale((float)S, (float)S, (float)S);
			if (facesCamera(bone)) {
				Vector3f at = world[bone].getTranslation(new Vector3f());
				float size = world[bone].getColumn(0, new Vector3f()).length();
				poseStack.translate(at.x, at.y, at.z);
				poseStack.rotate(facing);
				poseStack.scale(size, size, size);
			} else {
				poseStack.mulPose(world[bone]);
			}
			float[] c = sprite.color();
			Effects.quad(context, sprite.texture(), sprite.rect(), c[0], c[1], c[2], (float)alpha);
			poseStack.popPose();
		}
	}

	/** Whether the bone sits on (or is) an Explosion's sphere that has faded out. */
	private boolean gone(int bone) {
		for (int b = bone; b >= 0; b = this.model.parent(b)) {
			Explosion explosion = this.explosions.get(b);
			if (explosion != null && explosion.removed) {
				return true;
			}
		}
		return false;
	}

	private boolean facesCamera(int bone) {
		for (JsonElement element : this.model.scripts("alwaysLookAtCamera")) {
			if (element.getAsJsonObject().get("bone").getAsInt() == bone) {
				return true;
			}
		}
		return false;
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
