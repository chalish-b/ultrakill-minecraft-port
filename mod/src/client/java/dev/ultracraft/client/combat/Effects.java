package dev.ultracraft.client.combat;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.ultracraft.PlayerShape;
import dev.ultracraft.client.render.UltraModel;
import dev.ultracraft.combat.Gore;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** Beams, blood and impacts, explosions' looks, and drawing beams, coins, coin trails and shells in the world. */
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

	/** The blast wave's sphere material (ExplosionShockwave 3): its color, its glow, and its texture's alpha. */
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

	private final List<Beam> beams = new ArrayList<>();
	private final List<Wave> waves = new ArrayList<>();
	private final RandomSource random = RandomSource.create();
	private double clock;

	void update(double dt) {
		this.clock += dt;
		this.beams.removeIf(beam -> this.clock - beam.start > beam.style.life);
		this.waves.removeIf(wave -> wave.explosion.removed && spriteAlpha(wave.explosion) <= 0);
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

	void render(LevelRenderContext context, List<Coin> coins, List<Shell> shells) {
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
			sphere(context, model.mesh(0), explosion, camera);
			poseStack.popPose();
		}
		double alpha = spriteAlpha(explosion);
		if (alpha > 0 && !model.sprites().isEmpty()) {
			UltraModel.Sprite sprite = model.sprites().getFirst();
			double scale = model.rest().sx(0) * (model.rest().sx(sprite.bone()) + SPRITE_SCALE_SPEED * explosion.age()) * units;
			poseStack.pushPose();
			poseStack.scale((float)scale, (float)scale, (float)scale);
			float[] r = sprite.rect();
			float a = (float)alpha;
			context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.text(sprite.texture()), (pose, buffer) -> {
				float[][] corners = {{r[0], r[1], 0, 1}, {r[2], r[1], 1, 1}, {r[2], r[3], 1, 0}, {r[0], r[3], 0, 0}};
				for (int i = 0; i < 4; i++) {
					float[] c = corners[i];
					buffer.addVertex(pose, c[0], c[1], 0).setColor(1, 1, 1, a).setUv(c[2], c[3]).setLight(LightCoordsUtil.FULL_BRIGHT);
				}
				for (int i = 3; i >= 0; i--) {
					float[] c = corners[i];
					buffer.addVertex(pose, c[0], c[1], 0).setColor(1, 1, 1, a).setUv(c[2], c[3]).setLight(LightCoordsUtil.FULL_BRIGHT);
				}
			});
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
	 */
	private static void sphere(LevelRenderContext context, UltraModel.Mesh mesh, Explosion explosion, Vec3 camera) {
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
		float color = SPHERE_COLOR * SPHERE_TEXTURE_ALPHA * fade;
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
		float glow = SPHERE_TEXTURE_ALPHA * fade;
		context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, buffer) -> {
			for (int i = 0; i < indices.length; i += 3) {
				for (int v : new int[]{indices[i], indices[i + 1], indices[i + 2], indices[i + 2], indices[i + 2], indices[i + 2], indices[i + 1], indices[i]}) {
					buffer.addVertex(pose, p[v * 3], p[v * 3 + 1], -p[v * 3 + 2]).setColor(SPHERE_EMISSION, SPHERE_EMISSION, SPHERE_EMISSION, glow);
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
