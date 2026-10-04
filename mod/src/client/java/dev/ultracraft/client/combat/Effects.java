package dev.ultracraft.client.combat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.ultracraft.PlayerShape;
import dev.ultracraft.client.render.UltraModel;
import dev.ultracraft.combat.Gore;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Beams, blood and impacts, muzzle flashes, explosions' looks, and drawing beams, coins, coin trails, pellets and
 * shells in the world.
 */
final class Effects {
	/**
	 * The revolver beams' LineRenderers: a strip from the gun to what the shot hit, shaded along its length by
	 * color keys, narrowing away at 1.5 units a second (RevolverBeam's fade out). Their material is opaque.
	 */
	enum BeamStyle {
		/** "Revolver Beam": 0.25 units wide, white to orange. */
		REVOLVER(0.25, new float[][]{{0, 1, 1, 1}, {1, 1, 0.80689657F, 0}}),
		/** "Revolver Beam Super", the piercer's charged shot: 0.5 units wide, white to blue. */
		SUPER(0.5, new float[][]{{0, 1, 1, 1}, {1, 0, 0.8344827F, 1}}),
		/** "Revolver Beam Sharp", the sharpshooter's charged shot: 0.5 units wide, white to red. */
		SHARP(0.5, new float[][]{{0, 1, 1, 1}, {1, 1, 0.23529413F, 0.23529413F}}),
		/**
		 * "ReflectedBeamPoint", the coin's own beam when it passes a shot on or is punched: 0.5 units wide, orange,
		 * paler in the middle.
		 */
		COIN(0.5, new float[][]{{0, 1, 0.80784315F, 0}, {0.5F, 1, 0.9205858F, 0.7028302F}, {1, 1, 0.80784315F, 0}});

		/** Blocks. */
		final double width;
		/** Seconds until it has narrowed away. */
		final double life;
		/** Each key: where along the beam (0 to 1), then red, green and blue. */
		final float[][] keys;

		BeamStyle(double units, float[][] keys) {
			this.width = units * PlayerShape.BLOCKS_PER_UNIT;
			this.life = units / 1.5;
			this.keys = keys;
		}
	}

	private record Beam(Vec3 from, Vec3 to, BeamStyle style, double start) {
	}

	private static final BlockState GORE = Blocks.REDSTONE_BLOCK.defaultBlockState();
	private static final DustParticleOptions BLOOD = new DustParticleOptions(0x7A0000, 1.6F);
	/** The coin trail's width at its head (TrailRenderer widthMultiplier). */
	private static final double TRAIL_WIDTH = 0.2 * PlayerShape.BLOCKS_PER_UNIT;
	/** The coin trail's alpha at its head; it fades to nothing at the tail. */
	private static final float TRAIL_ALPHA = 0.49F;

	/**
	 * The blast wave's sphere material (ExplosionShockwave 3): its color, its glow, and its texture's alpha, which
	 * scales both as it adds them.
	 */
	private static final float SPHERE_COLOR = 0.17924529F;
	private static final float SPHERE_EMISSION = 0.4056604F;
	private static final float SPHERE_TEXTURE_ALPHA = 125 / 255F;
	/** The blast wave's Shockwave sprite grows by this much scale a second and fades at this much alpha (ScaleNFade). */
	private static final double SPRITE_SCALE_SPEED = 50;
	private static final double SPRITE_FADE_SPEED = 1;
	/** The blast wave's particles: a burst of sparks out of a 1 unit sphere, each trailing a streak back to where it started. */
	private static final int SPARK_COUNT = 50;
	private static final double SPARK_LIFETIME = 0.175;
	private static final double SPARK_SPEED = 200;
	/** The streaks' brightness: the Additive material doubles its tint (0.25) times the trail's gray (0.5). */
	private static final float SPARK_BRIGHTNESS = 0.25F;

	/** An explosion as the blast wave prefab draws it, with the sparks it threw. */
	private record Wave(Explosion explosion, List<Spark> sparks) {
	}

	/** Units: where it started, which way it flies, and its size (its streak's width at the head). */
	private record Spark(Vec3 start, Vec3 direction, double size) {
	}

	/** A pellet's yellow ("Basic Color - Yellow"); a boosted one's material is tinted (1, 0.35, 0). */
	private static final float[] PELLET = {1, 0.84705883F, 0};
	private static final float[] PELLET_BOOSTED = {1, 0.84705883F * 0.35F, 0};
	/** A pellet's trail: half a unit wide, narrowing to nothing, fading out over its 0.1 s; orange once boosted. */
	private static final double PELLET_TRAIL_WIDTH = 0.504;
	private static final float[] PELLET_TRAIL = {1, 0.77254903F, 0};
	private static final float[] PELLET_TRAIL_BOOSTED = {1, 0.35F, 0};
	/** The pellet ball: Unity's sphere at the projectile's 0.2 scale. */
	private static final double PELLET_RADIUS = 0.1;
	/** ShotgunMuzzleFlash: 24 times the sprite, shrinking and fading at 7.5 a second (SpriteController). */
	private static final double MUZZLE_SCALE = 24;
	private static final double MUZZLE_FADE = 7.5;
	/** BulletSpark: five sparks thrown at 1 to 10 units a second, a quarter unit across, falling and fading over half a second. */
	private static final int BULLET_SPARKS = 5;
	private static final double BULLET_SPARK_LIFETIME = 0.5;
	private static final double BULLET_SPARK_SIZE = 0.25;
	private static final double GRAVITY = 40;
	private static final float[] BULLET_SPARK_END = {1, 0.84779346F, 0};
	private static final Identifier SPARK = Identifier.fromNamespaceAndPath("ultracraft", "textures/viewmodel/spark.png");
	/** A core's trail: half a unit wide, yellow, fading out over a quarter of a second. */
	private static final double CORE_TRAIL_WIDTH = 0.524;
	private static final float[] CORE_TRAIL = {1, 0.8509804F, 0};
	/** A core's glow: the flash sprite at 24 times, on the core's 0.5 scale, blinking at 50 alpha a second. */
	private static final double CORE_GLOW_SCALE = 12;
	private static final double CORE_GLOW_BLINK = 50;
	private @Nullable RenderType coreGlow;

	private record MuzzleFlash(Vec3 position, Quaternionf rotation, double start) {
	}

	private record BulletSpark(Vec3 start, Vec3 velocity, double born) {
	}

	private final List<Beam> beams = new ArrayList<>();
	private final List<Wave> waves = new ArrayList<>();
	private final List<ExplosionLook> looks = new ArrayList<>();
	private final List<MuzzleFlash> muzzleFlashes = new ArrayList<>();
	private final List<BulletSpark> bulletSparks = new ArrayList<>();
	/** Particle systems played on their own, out of a prefab. */
	private final List<Particles> particles = new ArrayList<>();
	private final RandomSource random = RandomSource.create();
	private double clock;

	void update(double dt, Level level, Entity player) {
		this.clock += dt;
		this.beams.removeIf(beam -> this.clock - beam.start > beam.style.life);
		this.waves.removeIf(wave -> wave.explosion.removed && spriteAlpha(wave.explosion) <= 0);
		for (ExplosionLook look : this.looks) {
			look.update(dt, level, player);
		}
		this.looks.removeIf(ExplosionLook::done);
		this.muzzleFlashes.removeIf(flash -> (this.clock - flash.start) * MUZZLE_FADE >= 1);
		this.bulletSparks.removeIf(spark -> this.clock - spark.born > BULLET_SPARK_LIFETIME);
		for (Particles system : this.particles) {
			system.update(dt);
		}
		this.particles.removeIf(Particles::done);
	}

	/** A prefab's particle systems (or just the one called {@code name}), played once from {@code at}, unturned. */
	void particles(String prefab, @Nullable String name, Vec3 at) {
		UltraModel model = UltraModel.get(prefab);
		if (model == null) {
			return;
		}
		for (JsonElement element : model.particles()) {
			JsonObject system = element.getAsJsonObject();
			if (name == null || name.equals(system.get("name").getAsString())) {
				this.particles.add(new Particles(system, at, new Quaternionf(), ExplosionLook.unityRotation(model, system.get("bone").getAsInt())));
			}
		}
	}

	void explosion(ExplosionLook look) {
		this.looks.add(look);
	}

	/** A muzzle flash at the barrel, facing along {@code rotation} and turned a random whole number of degrees about it. */
	void muzzleFlash(Vec3 position, Quaternionf rotation) {
		Quaternionf rolled = new Quaternionf(rotation).rotateZ((float)Math.toRadians(this.random.nextInt(360)));
		this.muzzleFlashes.add(new MuzzleFlash(position, rolled, this.clock));
	}

	/** BulletSpark: sparks thrown out of a 0.1 unit sphere where a pellet landed. */
	void bulletSpark(Vec3 at) {
		for (int i = 0; i < BULLET_SPARKS; i++) {
			Vec3 point;
			do {
				point = new Vec3(this.random.nextDouble() * 2 - 1, this.random.nextDouble() * 2 - 1, this.random.nextDouble() * 2 - 1);
			} while (point.lengthSqr() > 1 || point.lengthSqr() == 0);
			Vec3 start = at.add(point.scale(0.1 * PlayerShape.BLOCKS_PER_UNIT));
			this.bulletSparks.add(new BulletSpark(start, point.normalize().scale(1 + this.random.nextDouble() * 9), this.clock));
		}
	}

	/** "Explosion Wave Knuckleblaster": the explosion's sphere, and its shockwave sprite and sparks. */
	void wave(Explosion explosion) {
		List<Spark> sparks = new ArrayList<>();
		for (int i = 0; i < SPARK_COUNT; i++) {
			Vec3 point;
			do {
				point = new Vec3(this.random.nextDouble() * 2 - 1, this.random.nextDouble() * 2 - 1, this.random.nextDouble() * 2 - 1);
			} while (point.lengthSqr() > 1 || point.lengthSqr() == 0);
			sparks.add(new Spark(point, point.normalize(), 0.1 + this.random.nextDouble() * 0.9));
		}
		this.waves.add(new Wave(explosion, sparks));
	}

	void beam(Vec3 from, Vec3 to, BeamStyle style) {
		this.beams.add(new Beam(from, to, style, this.clock));
	}

	void blood(Level level, Vec3 at, Gore gore) {
		int count = switch (gore) {
			case HEAD -> 30;
			case BODY -> 14;
			case SMALL -> 6;
		};
		for (int i = 0; i < count; i++) {
			double vx = this.random.nextGaussian() * 0.25;
			double vy = this.random.nextDouble() * 0.35;
			double vz = this.random.nextGaussian() * 0.25;
			level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, GORE), at.x, at.y, at.z, vx, vy, vz);
			if (i % 2 == 0) {
				level.addParticle(BLOOD, at.x, at.y, at.z, vx, vy, vz);
			}
		}
	}

	void impact(Level level, Vec3 at) {
		BlockState state = level.getBlockState(BlockPos.containing(at.subtract(0, 0.01, 0)));
		if (state.isAir()) {
			return;
		}
		for (int i = 0; i < 6; i++) {
			level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state), at.x, at.y, at.z,
				this.random.nextGaussian() * 0.1, this.random.nextDouble() * 0.15, this.random.nextGaussian() * 0.1);
		}
	}

	void render(LevelRenderContext context, List<Coin> coins, List<Shell> shells, List<Pellet> pellets, List<Core> cores) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			return;
		}
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		PoseStack poseStack = context.poseStack();
		if (!this.beams.isEmpty()) {
			context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.debugQuads(), (pose, buffer) -> {
				for (Beam beam : this.beams) {
					float fade = (float)(1 - (this.clock - beam.start) / beam.style.life);
					quad(pose, buffer, beam, camera, fade);
				}
			});
		}
		context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, buffer) -> {
			for (Coin coin : coins) {
				if (!coin.removed) {
					trail(pose, buffer, coin, camera);
				}
			}
		});
		for (Wave wave : this.waves) {
			renderWave(context, camera, wave);
		}
		for (ExplosionLook look : this.looks) {
			look.render(context, camera);
		}
		for (Particles system : this.particles) {
			system.render(context, camera);
		}
		renderPellets(context, camera, pellets);
		renderCores(context, camera, cores);
		renderMuzzleFlashes(context, camera);
		renderBulletSparks(context, camera);
		UltraModel shell = UltraModel.get("shotgun_shell");
		if (shell != null) {
			for (Shell s : shells) {
				poseStack.pushPose();
				poseStack.translate(s.position.x - camera.x, s.position.y - camera.y, s.position.z - camera.z);
				poseStack.rotate(s.rotation);
				poseStack.scale((float)PlayerShape.BLOCKS_PER_UNIT, (float)PlayerShape.BLOCKS_PER_UNIT, (float)PlayerShape.BLOCKS_PER_UNIT);
				shell.submit(poseStack, context.submitNodeCollector(), LightCoordsUtil.getLightCoords(minecraft.level, BlockPos.containing(s.position)));
				poseStack.popPose();
			}
		}
		UltraModel model = UltraModel.get("coin");
		if (model == null) {
			return;
		}
		for (Coin coin : coins) {
			if (coin.removed) {
				continue;
			}
			poseStack.pushPose();
			poseStack.translate(coin.position.x - camera.x, coin.position.y - camera.y, coin.position.z - camera.z);
			poseStack.rotate(Axis.YP.rotationDegrees(-coin.yaw));
			poseStack.rotate(Axis.XP.rotationDegrees(coin.pitch));
			poseStack.scale((float)PlayerShape.BLOCKS_PER_UNIT, (float)PlayerShape.BLOCKS_PER_UNIT, (float)PlayerShape.BLOCKS_PER_UNIT);
			model.submit(poseStack, context.submitNodeCollector(), LightCoordsUtil.getLightCoords(minecraft.level, BlockPos.containing(coin.position)));
			poseStack.popPose();
		}
		JsonObject script = model.script("coin");
		if (script != null && script.has("flash")) {
			JsonObject flash = script.getAsJsonObject("flash");
			Identifier texture = Identifier.parse(flash.get("texture").getAsString());
			double size = flash.get("size").getAsDouble() * PlayerShape.BLOCKS_PER_UNIT;
			for (Coin coin : coins) {
				if (!coin.removed && coin.flashAge >= 0) {
					flash(context, camera, coin, texture, size);
				}
			}
		}
	}

	/** Each pellet: a small glowing ball and its trail. */
	private static void renderPellets(LevelRenderContext context, Vec3 camera, List<Pellet> pellets) {
		if (pellets.isEmpty()) {
			return;
		}
		Quaternionf facing = Minecraft.getInstance().gameRenderer.mainCamera().rotation();
		context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.debugQuads(), (pose, buffer) -> {
			for (Pellet pellet : pellets) {
				pelletTrail(pose, buffer, pellet, camera);
				float[] color = pellet.boosted ? PELLET_BOOSTED : PELLET;
				// The ball, as a disc facing the camera: an unlit sphere looks the same from every side.
				Vec3 at = pellet.position.subtract(camera);
				float radius = (float)(PELLET_RADIUS * PlayerShape.BLOCKS_PER_UNIT);
				int sides = 8;
				for (int i = 0; i < sides; i++) {
					double a0 = Math.PI * 2 * i / sides;
					double a1 = Math.PI * 2 * (i + 1) / sides;
					Vector3f p0 = facing.transform(new Vector3f((float)Math.cos(a0) * radius, (float)Math.sin(a0) * radius, 0));
					Vector3f p1 = facing.transform(new Vector3f((float)Math.cos(a1) * radius, (float)Math.sin(a1) * radius, 0));
					Vector3f centre = new Vector3f((float)at.x, (float)at.y, (float)at.z);
					for (Vector3f v : new Vector3f[]{new Vector3f(centre), new Vector3f(centre).add(p0), new Vector3f(centre).add(p1), new Vector3f(centre)}) {
						buffer.addVertex(pose, v).setColor(color[0], color[1], color[2], 1);
					}
				}
			}
		});
	}

	private static void pelletTrail(PoseStack.Pose pose, VertexConsumer buffer, Pellet pellet, Vec3 camera) {
		trail(pose, buffer, pellet.position, pellet.trail, camera, PELLET_TRAIL_WIDTH, pellet.boosted ? PELLET_TRAIL_BOOSTED : PELLET_TRAIL);
	}

	/**
	 * A TrailRenderer: a strip through recent positions, turned to the camera, narrowing to nothing and fading
	 * out from its head to its tail.
	 *
	 * @param width units, at the head
	 */
	private static void trail(PoseStack.Pose pose, VertexConsumer buffer, Vec3 head, Iterable<Pellet.TrailPoint> trail, Vec3 camera, double width, float[] color) {
		List<Vec3> points = new ArrayList<>();
		points.add(head.subtract(camera));
		for (Pellet.TrailPoint point : trail) {
			if (!point.position().equals(head)) {
				points.add(point.position().subtract(camera));
			}
		}
		if (points.size() < 2) {
			return;
		}
		double[] along = new double[points.size()];
		for (int i = 1; i < points.size(); i++) {
			along[i] = along[i - 1] + points.get(i).distanceTo(points.get(i - 1));
		}
		double length = along[along.length - 1];
		if (length <= 0) {
			return;
		}
		for (int i = 0; i + 1 < points.size(); i++) {
			for (int j : new int[]{i, i + 1}) {
				double t = along[j] / length;
				Vec3 tangent = points.get(Math.min(j + 1, points.size() - 1)).subtract(points.get(Math.max(j - 1, 0)));
				Vec3 side = tangent.cross(points.get(j)).normalize().scale(width * PlayerShape.BLOCKS_PER_UNIT * (1 - t) / 2);
				if (!Double.isFinite(side.x)) {
					side = Vec3.ZERO;
				}
				float alpha = (float)Math.max(0, 1 - t);
				Vec3 a = points.get(j).add(side);
				Vec3 b = points.get(j).subtract(side);
				if (j == i) {
					buffer.addVertex(pose, new Vector3f((float)a.x, (float)a.y, (float)a.z)).setColor(color[0], color[1], color[2], alpha);
					buffer.addVertex(pose, new Vector3f((float)b.x, (float)b.y, (float)b.z)).setColor(color[0], color[1], color[2], alpha);
				} else {
					buffer.addVertex(pose, new Vector3f((float)b.x, (float)b.y, (float)b.z)).setColor(color[0], color[1], color[2], alpha);
					buffer.addVertex(pose, new Vector3f((float)a.x, (float)a.y, (float)a.z)).setColor(color[0], color[1], color[2], alpha);
				}
			}
		}
	}

	/**
	 * Each core: its model, lit by its own glow; its trail; and its glow, a camera-facing flash that blinks out
	 * and back 25 times a second (SpriteController, blinking at 50 a second), adding its color.
	 */
	private void renderCores(LevelRenderContext context, Vec3 camera, List<Core> cores) {
		UltraModel model = UltraModel.get("core");
		if (model == null || cores.isEmpty()) {
			return;
		}
		PoseStack poseStack = context.poseStack();
		float units = (float)PlayerShape.BLOCKS_PER_UNIT;
		context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.debugQuads(), (pose, buffer) -> {
			for (Core core : cores) {
				trail(pose, buffer, core.position, core.trail, camera, CORE_TRAIL_WIDTH, CORE_TRAIL);
			}
		});
		if (this.coreGlow == null && !model.sprites().isEmpty()) {
			this.coreGlow = RenderTypes.energySwirl(model.sprites().getFirst().texture(), 0, 0);
		}
		for (Core core : cores) {
			poseStack.pushPose();
			poseStack.translate(core.position.x - camera.x, core.position.y - camera.y, core.position.z - camera.z);
			poseStack.pushPose();
			poseStack.rotate(core.rotation);
			poseStack.scale(units, units, units);
			model.submit(poseStack, context.submitNodeCollector(), LightCoordsUtil.FULL_BRIGHT);
			poseStack.popPose();
			if (this.coreGlow != null) {
				UltraModel.Sprite glow = model.sprites().getFirst();
				float blink = (float)Math.abs(1 - (core.age() * CORE_GLOW_BLINK) % 2);
				poseStack.rotate(Minecraft.getInstance().gameRenderer.mainCamera().rotation());
				float scale = (float)(CORE_GLOW_SCALE * units);
				poseStack.scale(scale, scale, scale);
				float[] c = glow.color();
				quad(context, this.coreGlow, glow.rect(), c[0] * blink, c[1] * blink, c[2] * blink, 1);
			}
			poseStack.popPose();
		}
	}

	private void renderMuzzleFlashes(LevelRenderContext context, Vec3 camera) {
		UltraModel model = UltraModel.get("shotgun_muzzle_flash");
		if (model == null || model.sprites().isEmpty()) {
			return;
		}
		UltraModel.Sprite sprite = model.sprites().getFirst();
		PoseStack poseStack = context.poseStack();
		for (MuzzleFlash flash : this.muzzleFlashes) {
			double left = 1 - (this.clock - flash.start) * MUZZLE_FADE;
			poseStack.pushPose();
			poseStack.translate(flash.position.x - camera.x, flash.position.y - camera.y, flash.position.z - camera.z);
			poseStack.rotate(flash.rotation);
			float scale = (float)(MUZZLE_SCALE * left * PlayerShape.BLOCKS_PER_UNIT);
			poseStack.scale(scale, scale, scale);
			quad(context, sprite.texture(), sprite.rect(), 1, 1, 1, (float)left);
			poseStack.popPose();
		}
	}

	/** Each spark faces the camera, falls, and turns from white to yellow as it fades. */
	private void renderBulletSparks(LevelRenderContext context, Vec3 camera) {
		PoseStack poseStack = context.poseStack();
		Quaternionf facing = Minecraft.getInstance().gameRenderer.mainCamera().rotation();
		double units = PlayerShape.BLOCKS_PER_UNIT;
		for (BulletSpark spark : this.bulletSparks) {
			double t = this.clock - spark.born;
			double life = t / BULLET_SPARK_LIFETIME;
			Vec3 at = spark.start.add(spark.velocity.scale(t * units)).add(0, -0.5 * GRAVITY * t * t * units, 0);
			poseStack.pushPose();
			poseStack.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
			poseStack.rotate(facing);
			float size = (float)(BULLET_SPARK_SIZE * units);
			poseStack.scale(size, size, size);
			float green = (float)(1 + (BULLET_SPARK_END[1] - 1) * life);
			float blue = (float)(1 - life);
			quad(context, SPARK, new float[]{-0.5F, -0.5F, 0.5F, 0.5F}, 1, green, blue, (float)(1 - life));
			poseStack.popPose();
		}
	}

	/** A sprite's rectangle at the current pose, from both sides, its texture tinted and faded. */
	static void quad(LevelRenderContext context, Identifier texture, float[] r, float red, float green, float blue, float alpha) {
		quad(context, RenderTypes.text(texture), r, red, green, blue, alpha);
	}

	/** Like {@link #quad(LevelRenderContext, Identifier, float[], float, float, float, float)}, in a render type that takes a texture. */
	static void quad(LevelRenderContext context, RenderType type, float[] r, float red, float green, float blue, float alpha) {
		context.submitNodeCollector().submitCustomGeometry(context.poseStack(), type, (pose, buffer) -> {
			float[][] corners = {{r[0], r[1], 0, 1}, {r[2], r[1], 1, 1}, {r[2], r[3], 1, 0}, {r[0], r[3], 0, 0}};
			for (int i = 0; i < 4; i++) {
				float[] c = corners[i];
				buffer.addVertex(pose, c[0], c[1], 0).setColor(red, green, blue, alpha).setUv(c[2], c[3])
					.setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 0, 1);
			}
			for (int i = 3; i >= 0; i--) {
				float[] c = corners[i];
				buffer.addVertex(pose, c[0], c[1], 0).setColor(red, green, blue, alpha).setUv(c[2], c[3])
					.setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 0, 1);
			}
		});
	}

	/** The Shockwave sprite's alpha: its color's, fading from when the explosion appeared. */
	private static double spriteAlpha(Explosion explosion) {
		return 0.686274528503418 - SPRITE_FADE_SPEED * explosion.age();
	}

	private void renderWave(LevelRenderContext context, Vec3 camera, Wave wave) {
		UltraModel model = UltraModel.get("explosion_wave_knuckleblaster");
		if (model == null) {
			return;
		}
		Explosion explosion = wave.explosion;
		PoseStack poseStack = context.poseStack();
		poseStack.pushPose();
		poseStack.translate(explosion.origin.x - camera.x, explosion.origin.y - camera.y, explosion.origin.z - camera.z);
		poseStack.rotate(explosion.rotation);
		float units = (float)PlayerShape.BLOCKS_PER_UNIT;
		if (!explosion.removed) {
			poseStack.pushPose();
			float scale = (float)explosion.meshScale() * units;
			poseStack.scale(scale, scale, scale);
			sphere(context, model.mesh(0), explosion, camera, SPHERE_COLOR * SPHERE_TEXTURE_ALPHA, SPHERE_EMISSION * SPHERE_TEXTURE_ALPHA);
			poseStack.popPose();
		}
		double alpha = spriteAlpha(explosion);
		if (alpha > 0 && !model.sprites().isEmpty()) {
			UltraModel.Sprite sprite = model.sprites().getFirst();
			double scale = model.rest().sx(0) * (model.rest().sx(sprite.bone()) + SPRITE_SCALE_SPEED * explosion.age()) * units;
			poseStack.pushPose();
			poseStack.scale((float)scale, (float)scale, (float)scale);
			quad(context, sprite.texture(), sprite.rect(), 1, 1, 1, (float)alpha);
			poseStack.popPose();
		}
		poseStack.popPose();
		if (explosion.age() < SPARK_LIFETIME) {
			context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, buffer) -> {
				for (Spark spark : wave.sparks) {
					spark(pose, buffer, explosion, spark, camera);
				}
			});
		}
	}

	/**
	 * The explosion's sphere. For its first tenth of a second it's plain white, seen from outside only; then it
	 * shows its material from both sides, adding its texture and glow to what's behind it and fading as it goes.
	 *
	 * @param textured how much of its texture it adds
	 * @param glow how much plain light it adds
	 */
	static void sphere(LevelRenderContext context, UltraModel.Mesh mesh, Explosion explosion, Vec3 camera, float textured, float glow) {
		float[] p = mesh.positions();
		float[] n = mesh.normals();
		float[] uv = mesh.uvs();
		int[] indices = mesh.indices();
		PoseStack poseStack = context.poseStack();
		if (explosion.age() <= Explosion.WHITE_TIME) {
			Vec3 toCamera = camera.subtract(explosion.origin);
			Quaternionf inverse = new Quaternionf(explosion.rotation).conjugate();
			Vector3f view = inverse.transform(new Vector3f((float)toCamera.x, (float)toCamera.y, (float)toCamera.z));
			float scale = (float)(explosion.meshScale() * PlayerShape.BLOCKS_PER_UNIT);
			context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.debugQuads(), (pose, buffer) -> {
				for (int i = 0; i < indices.length; i += 3) {
					int a = indices[i], b = indices[i + 1], c = indices[i + 2];
					float cx = (p[a * 3] + p[b * 3] + p[c * 3]) / 3 * scale;
					float cy = (p[a * 3 + 1] + p[b * 3 + 1] + p[c * 3 + 1]) / 3 * scale;
					float cz = -(p[a * 3 + 2] + p[b * 3 + 2] + p[c * 3 + 2]) / 3 * scale;
					float nx = n[a * 3] + n[b * 3] + n[c * 3];
					float ny = n[a * 3 + 1] + n[b * 3 + 1] + n[c * 3 + 1];
					float nz = -(n[a * 3 + 2] + n[b * 3 + 2] + n[c * 3 + 2]);
					if (nx * (view.x - cx) + ny * (view.y - cy) + nz * (view.z - cz) <= 0) {
						continue;
					}
					for (int v : new int[]{a, b, c, c}) {
						buffer.addVertex(pose, p[v * 3], p[v * 3 + 1], -p[v * 3 + 2]).setColor(1F, 1F, 1F, 1F);
					}
				}
			});
			return;
		}
		float fade = (float)Math.max(explosion.alpha(), 0);
		float color = textured * fade;
		context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.energySwirl(mesh.texture(), 0, 0), (pose, buffer) -> {
			for (int i = 0; i < indices.length; i += 3) {
				for (int v : new int[]{indices[i], indices[i + 1], indices[i + 2], indices[i + 2]}) {
					buffer.addVertex(pose, p[v * 3], p[v * 3 + 1], -p[v * 3 + 2])
						.setColor(color, color, color, 1)
						.setUv(uv[v * 2], uv[v * 2 + 1])
						.setOverlay(OverlayTexture.NO_OVERLAY)
						.setLight(LightCoordsUtil.FULL_BRIGHT)
						.setNormal(pose, n[v * 3], n[v * 3 + 1], -n[v * 3 + 2]);
				}
			}
		});
		if (glow <= 0) {
			return;
		}
		float light = glow * fade;
		context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, buffer) -> {
			for (int i = 0; i < indices.length; i += 3) {
				for (int v : new int[]{indices[i], indices[i + 1], indices[i + 2], indices[i + 2], indices[i + 2], indices[i + 2], indices[i + 1], indices[i]}) {
					buffer.addVertex(pose, p[v * 3], p[v * 3 + 1], -p[v * 3 + 2]).setColor(light, light, light, 1F);
				}
			}
		});
	}

	/**
	 * A spark's streak, from where it started to where it is, turned to face the camera: full width at the spark
	 * and none at the start, and fading towards the start as its trail ages out.
	 */
	private static void spark(PoseStack.Pose pose, VertexConsumer buffer, Explosion explosion, Spark spark, Vec3 camera) {
		double units = PlayerShape.BLOCKS_PER_UNIT;
		double t = explosion.age();
		Vec3 start = explosion.origin.add(spark.start.scale(units)).subtract(camera);
		Vec3 head = start.add(spark.direction.scale(SPARK_SPEED * t * units));
		Vec3 side = spark.direction.cross(head).normalize().scale(spark.size * units / 2);
		if (!Double.isFinite(side.x)) {
			return;
		}
		// The Additive shader doubles the trail's alpha, which runs from 1 at the spark to 0 where a point is a
		// lifetime old.
		float tailAlpha = (float)Math.min(1, 2 * (1 - t / SPARK_LIFETIME));
		float b = SPARK_BRIGHTNESS;
		for (Vec3[] quad : new Vec3[][]{{head.add(side), head.subtract(side), start, start}, {start, start, head.subtract(side), head.add(side)}}) {
			for (int i = 0; i < 4; i++) {
				Vec3 v = quad[i];
				boolean atHead = v != start;
				buffer.addVertex(pose, new Vector3f((float)v.x, (float)v.y, (float)v.z)).setColor(b, b, b, atHead ? 1 : tailAlpha);
			}
		}
	}

	/** The coin's flash: a sprite facing the camera that shrinks and fades (SpriteController, both at 1 a second). */
	private static void flash(LevelRenderContext context, Vec3 camera, Coin coin, Identifier texture, double size) {
		float left = (float)(1 - coin.flashAge);
		float half = (float)(size * left / 2);
		PoseStack poseStack = context.poseStack();
		poseStack.pushPose();
		poseStack.translate(coin.position.x - camera.x, coin.position.y - camera.y, coin.position.z - camera.z);
		poseStack.rotate(Minecraft.getInstance().gameRenderer.mainCamera().rotation());
		context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.text(texture), (pose, buffer) -> {
			float[][] corners = {{-half, -half, 0, 1}, {half, -half, 1, 1}, {half, half, 1, 0}, {-half, half, 0, 0}};
			for (float[] c : corners) {
				buffer.addVertex(pose, c[0], c[1], 0).setColor(1, 1, 1, left).setUv(c[2], c[3]).setLight(LightCoordsUtil.FULL_BRIGHT);
			}
		});
		poseStack.popPose();
	}

	/** A flat strip from one end of the beam to the other, turned to face the camera, a piece per color key. */
	private static void quad(PoseStack.Pose pose, VertexConsumer buffer, Beam beam, Vec3 camera, float fade) {
		Vec3 from = beam.from.subtract(camera);
		Vec3 to = beam.to.subtract(camera);
		Vec3 along = to.subtract(from);
		Vec3 side = along.cross(from.add(to).scale(0.5)).normalize().scale(beam.style.width * fade / 2);
		float[][] keys = beam.style.keys;
		for (int i = 0; i + 1 < keys.length; i++) {
			Vec3 a = from.add(along.scale(keys[i][0]));
			Vec3 b = from.add(along.scale(keys[i + 1][0]));
			corner(pose, buffer, a.add(side), keys[i]);
			corner(pose, buffer, a.subtract(side), keys[i]);
			corner(pose, buffer, b.subtract(side), keys[i + 1]);
			corner(pose, buffer, b.add(side), keys[i + 1]);
		}
	}

	/**
	 * The coin's TrailRenderer: a strip through its recent positions, turned to face the camera, white and
	 * additive, narrowing and fading from the coin to the tail.
	 */
	private static void trail(PoseStack.Pose pose, VertexConsumer buffer, Coin coin, Vec3 camera) {
		List<Vec3> points = new ArrayList<>();
		points.add(coin.position.subtract(camera));
		for (Coin.TrailPoint point : coin.trail) {
			if (coin.age() - point.time() <= Coin.TRAIL_TIME && !point.position().equals(coin.position)) {
				points.add(point.position().subtract(camera));
			}
		}
		if (points.size() < 2) {
			return;
		}
		double[] along = new double[points.size()];
		for (int i = 1; i < points.size(); i++) {
			along[i] = along[i - 1] + points.get(i).distanceTo(points.get(i - 1));
		}
		double length = along[along.length - 1];
		if (length <= 0) {
			return;
		}
		Vec3[] sides = new Vec3[points.size()];
		for (int i = 0; i < points.size(); i++) {
			Vec3 tangent = points.get(Math.min(i + 1, points.size() - 1)).subtract(points.get(Math.max(i - 1, 0)));
			double t = along[i] / length;
			double width = TRAIL_WIDTH * (1 - t * t * (3 - 2 * t));
			Vec3 side = tangent.cross(points.get(i)).normalize();
			sides[i] = side.scale(width / 2);
		}
		for (int i = 0; i + 1 < points.size(); i++) {
			float headAlpha = (float)(TRAIL_ALPHA * (1 - along[i] / length));
			float tailAlpha = (float)(TRAIL_ALPHA * (1 - along[i + 1] / length));
			trailCorner(pose, buffer, points.get(i).add(sides[i]), headAlpha);
			trailCorner(pose, buffer, points.get(i).subtract(sides[i]), headAlpha);
			trailCorner(pose, buffer, points.get(i + 1).subtract(sides[i + 1]), tailAlpha);
			trailCorner(pose, buffer, points.get(i + 1).add(sides[i + 1]), tailAlpha);
		}
	}

	private static void trailCorner(PoseStack.Pose pose, VertexConsumer buffer, Vec3 at, float alpha) {
		buffer.addVertex(pose, new Vector3f((float)at.x, (float)at.y, (float)at.z)).setColor(1, 1, 1, alpha);
	}

	private static void corner(PoseStack.Pose pose, VertexConsumer buffer, Vec3 at, float[] key) {
		buffer.addVertex(pose, new Vector3f((float)at.x, (float)at.y, (float)at.z)).setColor(key[1], key[2], key[3], 1);
	}
}
