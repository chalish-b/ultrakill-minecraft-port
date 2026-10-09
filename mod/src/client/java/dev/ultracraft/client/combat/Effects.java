package dev.ultracraft.client.combat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.ultracraft.PlayerShape;
import dev.ultracraft.client.render.UltraModel;
import dev.ultracraft.client.render.animation.Pose;
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
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Beams, blood and impacts, muzzle flashes, explosions' looks, electric arcs, and drawing beams, coins, coin trails,
 * pellets, shells, nails, magnets and the zapper in the world.
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
		COIN(0.5, new float[][]{{0, 1, 0.80784315F, 0}, {0.5F, 1, 0.9205858F, 0.7028302F}, {1, 1, 0.80784315F, 0}}),
		/** "Railcannon Beam", the Electric railcannon's: 1 unit wide, white to blue, with its arcs and glow over it. */
		RAILCANNON(1, new float[][]{{0, 1, 1, 1}, {1, 0, 0.8344827F, 1}}, "railcannon_beam"),
		/** "Railcannon Beam Malicious": 1 unit wide, white to orange, with a glow over it. */
		MALICIOUS(1, new float[][]{{0, 1, 1, 1}, {1, 1, 0.6308285F, 0}}, "railcannon_beam_malicious");

		/** Blocks. */
		final double width;
		/** Seconds until it has narrowed away. */
		final double life;
		/** Each key: where along the beam (0 to 1), then red, green and blue. */
		final float[][] keys;
		/** The extracted beam prefab whose other LineRenderers follow this one, if it has any. */
		final @Nullable String prefab;

		BeamStyle(double units, float[][] keys) {
			this(units, keys, null);
		}

		BeamStyle(double units, float[][] keys, @Nullable String prefab) {
			this.width = units * PlayerShape.BLOCKS_PER_UNIT;
			this.life = units / 1.5;
			this.keys = keys;
			this.prefab = prefab;
		}
	}

	/** A beam drawn from one point to another; it narrows away once it's fading. */
	static final class Beam {
		final Vec3 from;
		final Vec3 to;
		final BeamStyle style;
		/** When it started narrowing, or infinity while it's held at full width. */
		private double fadeStart;
		/**
		 * Its ElectricityLine's current pick, swapped every 0.05 s: the arc frame, the line's width (units), and its
		 * colors at the start and the end.
		 */
		private double arcCooldown;
		private int arcFrame;
		private double arcWidth;
		private float[] arcStart = {1, 1, 1, 1};
		private float[] arcEnd = {1, 1, 1, 1};

		private Beam(Vec3 from, Vec3 to, BeamStyle style, double fadeStart) {
			this.from = from;
			this.to = to;
			this.style = style;
			this.fadeStart = fadeStart;
		}
	}

	/** ElectricityLine swaps its pick this often. */
	private static final double ARC_INTERVAL = 0.05;

	/**
	 * A muzzle flash prefab's sprite (its SpriteController): turned a random whole number of degrees, it shrinks and
	 * fades, and is gone after its lifetime (RemoveOnTime) if it has one.
	 */
	private record PrefabFlash(Vec3 position, Quaternionf rotation, String prefab, double start, double lifetime, double scale) {
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

	/** "ElectricityLine": a zap's arc from one point to another, fading out (its ElectricityLine's fadeSpeed). */
	private static final class Arc {
		final Vec3 from;
		final Vec3 to;
		final double start;
		/** Its current pick, swapped every 0.05 s: the arc frame, the width (units) and the colors at either end. */
		double cooldown;
		int frame;
		double width;
		float[] startColor = {1, 1, 1, 1};
		float[] endColor = {1, 1, 1, 1};

		Arc(Vec3 from, Vec3 to, double start) {
			this.from = from;
			this.to = to;
			this.start = start;
		}
	}

	private static final String ELECTRIC_LINE = "electric_line";

	private record BulletSpark(Vec3 start, Vec3 velocity, double born) {
	}

	/** The saws' running blade material; and the chainsaw's glint, spinning (its Spin) and shrunk back at the player. */
	private static final String SAW_BLADE = "ChainsawBladeMotion";
	/** HookArm's longest wire (units), which its texture's tiling and running go by. */
	private static final double WIRE_LENGTH = 300;
	private static final double SAW_GLINT_SPIN = 3600;
	private static final float SAW_GLINT_SHRUNK = 0.2F;

	private final List<Beam> beams = new ArrayList<>();
	private final List<Wave> waves = new ArrayList<>();
	private final List<ExplosionLook> looks = new ArrayList<>();
	private final List<MuzzleFlash> muzzleFlashes = new ArrayList<>();
	private final List<PrefabFlash> prefabFlashes = new ArrayList<>();
	private final List<BulletSpark> bulletSparks = new ArrayList<>();
	private final List<Arc> arcs = new ArrayList<>();
	/** Particle systems played on their own, out of a prefab. */
	private final List<Particles> particles = new ArrayList<>();
	private final RandomSource random = RandomSource.create();
	private double clock;
	/** How long the frame under way is. */
	private double frameTime;

	void update(double dt, Level level, Entity player) {
		this.clock += dt;
		this.frameTime = dt;
		this.beams.removeIf(beam -> this.clock - beam.fadeStart > beam.style.life);
		for (Beam beam : this.beams) {
			beam.arcCooldown -= dt;
			if (beam.arcCooldown <= 0) {
				pickArc(beam);
			}
		}
		this.prefabFlashes.removeIf(flash -> flashScale(flash) <= 0 || flashAlpha(flash) <= 0 || this.clock - flash.start >= flash.lifetime);
		this.waves.removeIf(wave -> wave.explosion.removed && spriteAlpha(wave.explosion) <= 0);
		for (ExplosionLook look : this.looks) {
			look.update(dt, level, player);
		}
		this.looks.removeIf(ExplosionLook::done);
		this.muzzleFlashes.removeIf(flash -> (this.clock - flash.start) * MUZZLE_FADE >= 1);
		this.bulletSparks.removeIf(spark -> this.clock - spark.born > BULLET_SPARK_LIFETIME);
		JsonObject electricity = electricityLine();
		if (electricity != null) {
			this.arcs.removeIf(arc -> arcFade(arc, electricity) <= 0 || this.clock - arc.start >= electricity.get("lifetime").getAsDouble());
			for (Arc arc : this.arcs) {
				arc.cooldown -= dt;
				if (arc.cooldown <= 0) {
					pickArc(arc, electricity);
				}
			}
		}
		for (Particles system : this.particles) {
			system.update(dt);
		}
		this.particles.removeIf(Particles::done);
	}

	/** A prefab's particle systems (or just the one called {@code name}), played once from {@code at}, unturned. */
	void particles(String prefab, @Nullable String name, Vec3 at) {
		particles(prefab, name, at, new Quaternionf());
	}

	/** Like {@link #particles(String, String, Vec3)}, with the prefab turned to {@code rotation} (as Minecraft draws it). */
	void particles(String prefab, @Nullable String name, Vec3 at, Quaternionf rotation) {
		UltraModel model = UltraModel.get(prefab);
		if (model == null) {
			return;
		}
		for (JsonElement element : model.particles()) {
			JsonObject system = element.getAsJsonObject();
			if (name == null || name.equals(system.get("name").getAsString())) {
				this.particles.add(new Particles(system, at, rotation, ExplosionLook.unityRotation(model, system.get("bone").getAsInt())));
			}
		}
	}

	/** A particle system played until it's done, which its owner can still move, turn and stop. */
	void play(Particles system) {
		this.particles.add(system);
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
		Beam beam = new Beam(from, to, style, this.clock);
		pickArc(beam);
		this.beams.add(beam);
	}

	/** A beam held at full width until it's {@link #fade faded}, as a piercing shot's is while it works through what it hit. */
	Beam heldBeam(Vec3 from, Vec3 to, BeamStyle style) {
		Beam beam = new Beam(from, to, style, Double.POSITIVE_INFINITY);
		pickArc(beam);
		this.beams.add(beam);
		return beam;
	}

	/** ElectricityLine.Update: a random arc frame, width between its two, and colors along its gradient for both ends. */
	private void pickArc(Beam beam) {
		beam.arcCooldown = ARC_INTERVAL;
		JsonObject electricity = electricity(beam.style);
		if (electricity == null) {
			return;
		}
		beam.arcFrame = this.random.nextInt(electricity.getAsJsonArray("frames").size());
		double min = electricity.get("minWidth").getAsDouble();
		beam.arcWidth = min + this.random.nextDouble() * (electricity.get("maxWidth").getAsDouble() - min);
		beam.arcStart = gradientAt(electricity.getAsJsonObject("colors"), this.random.nextDouble());
		beam.arcEnd = gradientAt(electricity.getAsJsonObject("colors"), this.random.nextDouble());
	}

	/** A zap's arc from {@code from} to {@code to}. */
	void arc(Vec3 from, Vec3 to) {
		JsonObject electricity = electricityLine();
		if (electricity == null) {
			return;
		}
		Arc arc = new Arc(from, to, this.clock);
		pickArc(arc, electricity);
		this.arcs.add(arc);
	}

	private static @Nullable JsonObject electricityLine() {
		UltraModel model = UltraModel.get(ELECTRIC_LINE);
		return model == null ? null : model.script("electricityLine");
	}

	/** ElectricityLine.Update's pick: a random arc frame, width between its two, and colors along its gradient for both ends. */
	private void pickArc(Arc arc, JsonObject electricity) {
		arc.cooldown = ARC_INTERVAL;
		arc.frame = this.random.nextInt(electricity.getAsJsonArray("frames").size());
		double min = electricity.get("minWidth").getAsDouble();
		arc.width = min + this.random.nextDouble() * (electricity.get("maxWidth").getAsDouble() - min);
		arc.startColor = gradientAt(electricity.getAsJsonObject("colors"), this.random.nextDouble());
		arc.endColor = gradientAt(electricity.getAsJsonObject("colors"), this.random.nextDouble());
	}

	/** How much of the arc is left as it fades: 1 to 0. */
	private double arcFade(Arc arc, JsonObject electricity) {
		return 1 - (this.clock - arc.start) * electricity.get("fadeSpeed").getAsDouble();
	}

	/** Each arc: a strip from end to end facing the camera, its frame stretched along it, faded. */
	private void renderArcs(LevelRenderContext context, Vec3 camera) {
		JsonObject electricity = electricityLine();
		if (electricity == null) {
			return;
		}
		for (Arc arc : this.arcs) {
			float fade = (float)Math.clamp(arcFade(arc, electricity), 0, 1);
			Identifier texture = Identifier.parse(electricity.getAsJsonArray("frames").get(arc.frame).getAsString());
			Vec3 from = arc.from.subtract(camera);
			Vec3 to = arc.to.subtract(camera);
			Vec3 side = to.subtract(from).cross(from.add(to).scale(0.5)).normalize().scale(arc.width * PlayerShape.BLOCKS_PER_UNIT / 2);
			if (!Double.isFinite(side.x)) {
				continue;
			}
			float[] a = {arc.startColor[0], arc.startColor[1], arc.startColor[2], arc.startColor[3] * fade};
			float[] b = {arc.endColor[0], arc.endColor[1], arc.endColor[2], arc.endColor[3] * fade};
			float[] tint = {1, 1, 1, 1};
			context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.beaconBeam(texture, true), (pose, buffer) -> {
				lineCorner(pose, buffer, from.add(side), 0, 0, a, tint);
				lineCorner(pose, buffer, from.subtract(side), 0, 1, a, tint);
				lineCorner(pose, buffer, to.subtract(side), 1, 1, b, tint);
				lineCorner(pose, buffer, to.add(side), 1, 0, b, tint);
				lineCorner(pose, buffer, to.add(side), 1, 0, b, tint);
				lineCorner(pose, buffer, to.subtract(side), 1, 1, b, tint);
				lineCorner(pose, buffer, from.subtract(side), 0, 1, a, tint);
				lineCorner(pose, buffer, from.add(side), 0, 0, a, tint);
			});
		}
	}

	/** The style's beam prefab line that has an ElectricityLine, if any. */
	private static @Nullable JsonObject electricity(BeamStyle style) {
		UltraModel model = style.prefab == null ? null : UltraModel.get(style.prefab);
		JsonObject script = model == null ? null : model.script("beam");
		if (script == null) {
			return null;
		}
		for (JsonElement line : script.getAsJsonArray("lines")) {
			if (line.getAsJsonObject().has("electricity")) {
				return line.getAsJsonObject().getAsJsonObject("electricity");
			}
		}
		return null;
	}

	/**
	 * The beam's muzzle flash at the barrel (RevolverBeam puts it there, turned the way the beam goes): its prefab's
	 * flash sprite, facing along {@code rotation} (the camera's).
	 */
	void beamFlash(Vec3 position, Quaternionf rotation, BeamStyle style) {
		if (style.prefab != null) {
			Quaternionf rolled = new Quaternionf(rotation).rotateZ((float)Math.toRadians(this.random.nextInt(360)));
			this.prefabFlashes.add(new PrefabFlash(position, rolled, style.prefab, this.clock, Double.POSITIVE_INFINITY, 1));
		}
	}

	/**
	 * A muzzle flash made in a physics step: the prefab's flash sprite at {@code position}, facing along
	 * {@code rotation}, drawn {@code scale} times its size, for at most {@code lifetime} seconds. Its SpriteController
	 * has already shrunk and faded it by this frame's time when it's first drawn.
	 */
	void stepFlash(String prefab, Vec3 position, Quaternionf rotation, double lifetime, double scale) {
		Quaternionf rolled = new Quaternionf(rotation).rotateZ((float)Math.toRadians(this.random.nextInt(360)));
		this.prefabFlashes.add(new PrefabFlash(position, rolled, prefab, this.clock - this.frameTime, lifetime, scale));
	}

	/** The flash sprite's world scale (its parents', times its own as it shrinks), in units. */
	private double flashScale(PrefabFlash flash) {
		UltraModel model = UltraModel.get(flash.prefab);
		if (model == null || model.sprites().isEmpty()) {
			return 0;
		}
		int bone = model.sprites().getFirst().bone();
		double parents = 1;
		for (int b = model.parent(bone); b >= 0; b = model.parent(b)) {
			parents *= model.rest().sx(b);
		}
		return parents * (model.rest().sx(bone) - spriteController(model, bone, "shrinkSpeed") * (this.clock - flash.start));
	}

	private double flashAlpha(PrefabFlash flash) {
		UltraModel model = UltraModel.get(flash.prefab);
		if (model == null || model.sprites().isEmpty()) {
			return 0;
		}
		UltraModel.Sprite sprite = model.sprites().getFirst();
		return sprite.color()[3] - spriteController(model, sprite.bone(), "fadeSpeed") * (this.clock - flash.start);
	}

	private static double spriteController(UltraModel model, int bone, String field) {
		for (JsonElement controller : model.scripts("spriteController")) {
			if (controller.getAsJsonObject().get("bone").getAsInt() == bone) {
				return controller.getAsJsonObject().get(field).getAsDouble();
			}
		}
		return 0;
	}

	private void renderPrefabFlashes(LevelRenderContext context, Vec3 camera) {
		PoseStack poseStack = context.poseStack();
		for (PrefabFlash flash : this.prefabFlashes) {
			UltraModel model = UltraModel.get(flash.prefab);
			double scale = flashScale(flash);
			double alpha = flashAlpha(flash);
			if (model == null || scale <= 0 || alpha <= 0) {
				continue;
			}
			UltraModel.Sprite sprite = model.sprites().getFirst();
			poseStack.pushPose();
			poseStack.translate(flash.position.x - camera.x, flash.position.y - camera.y, flash.position.z - camera.z);
			poseStack.rotate(flash.rotation);
			float size = (float)(scale * flash.scale * PlayerShape.BLOCKS_PER_UNIT);
			poseStack.scale(size, size, size);
			float[] color = sprite.color();
			quad(context, RenderTypes.beaconBeam(sprite.texture(), true), sprite.rect(), color[0], color[1], color[2], (float)alpha);
			poseStack.popPose();
		}
	}

	void fade(Beam beam) {
		beam.fadeStart = Math.min(beam.fadeStart, this.clock);
	}

	void blood(Level level, Vec3 at, Gore gore) {
		blood(level, at, gore, 1);
	}

	/** Blood from a splatter {@code scale} times the usual size, which throws it that much further. */
	void blood(Level level, Vec3 at, Gore gore, float scale) {
		int count = switch (gore) {
			case HEAD -> 30;
			case BODY -> 14;
			case SMALL -> 6;
		};
		for (int i = 0; i < count; i++) {
			double vx = this.random.nextGaussian() * 0.25 * scale;
			double vy = this.random.nextDouble() * 0.35 * scale;
			double vz = this.random.nextGaussian() * 0.25 * scale;
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
					quad(pose, buffer, beam, camera, beamWidth(beam));
				}
			});
			for (Beam beam : this.beams) {
				renderLines(context, beam, camera, beamWidth(beam));
			}
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
		renderPrefabFlashes(context, camera);
		renderBulletSparks(context, camera);
		renderArcs(context, camera);
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

	/**
	 * The thrown chainsaws and sawblades: each one's trail and model, its blade running. A chainsaw also has its
	 * chain from {@code chainStart} and its spinning glint, which is all that shows (and smaller) while it's back
	 * at the player.
	 */
	void renderSaws(LevelRenderContext context, List<ThrownSaw> saws, List<Sawblade> sawblades, @Nullable Vec3 chainStart) {
		Minecraft minecraft = Minecraft.getInstance();
		UltraModel thrown = UltraModel.get("chainsaw_thrown");
		UltraModel blade = UltraModel.get("chainsaw_sawblade");
		if (minecraft.level == null || thrown == null || blade == null || saws.isEmpty() && sawblades.isEmpty()) {
			return;
		}
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		JsonObject thrownTrail = thrown.script("chainsaw").getAsJsonObject("trail");
		JsonObject bladeTrail = blade.script("sawblade").getAsJsonObject("trail");
		context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.lightning(), (pose, buffer) -> {
			for (ThrownSaw saw : saws) {
				sawTrail(pose, buffer, saw.position, saw.trail, camera, thrownTrail);
			}
			for (Sawblade sawblade : sawblades) {
				sawTrail(pose, buffer, sawblade.position, sawblade.trail, camera, bladeTrail);
			}
		});
		if (chainStart != null) {
			JsonObject line = thrown.script("chainsaw").getAsJsonObject("line");
			Identifier texture = Identifier.parse(line.getAsJsonObject("material").get("texture").getAsString());
			double width = line.get("width").getAsDouble() * PlayerShape.BLOCKS_PER_UNIT;
			context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.entityCutout(texture), (pose, buffer) -> {
				for (ThrownSaw saw : saws) {
					chain(pose, buffer, chainStart.subtract(camera), saw.position.subtract(camera), width);
				}
			});
		}
		for (ThrownSaw saw : saws) {
			submitSaw(context, thrown, saw.position, saw.forward, saw.bladeScroll, camera, !saw.inPlayer, saw.inPlayer ? SAW_GLINT_SHRUNK : 1, saw.age());
		}
		for (Sawblade sawblade : sawblades) {
			submitSaw(context, blade, sawblade.position, sawblade.forward(), sawblade.bladeScroll, camera, true, 0, sawblade.age());
		}
	}

	/**
	 * The Screwdriver's drills: each one's trail and model, spinning about its length, and its TimeBomb's beeper
	 * flashing on it.
	 */
	void renderDrills(LevelRenderContext context, List<Drill> drills) {
		Minecraft minecraft = Minecraft.getInstance();
		UltraModel model = UltraModel.get("drill");
		if (minecraft.level == null || model == null || drills.isEmpty()) {
			return;
		}
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		JsonObject trail = model.script("harpoon").getAsJsonObject("trail");
		context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.lightning(), (pose, buffer) -> {
			for (Drill drill : drills) {
				sawTrail(pose, buffer, drill.position, drill.trail, camera, trail);
			}
		});
		UltraModel beeper = UltraModel.get("drill_beeper");
		PoseStack poseStack = context.poseStack();
		float units = (float)PlayerShape.BLOCKS_PER_UNIT;
		for (Drill drill : drills) {
			Vec3 forward = drill.forward;
			poseStack.pushPose();
			poseStack.translate(drill.position.x - camera.x, drill.position.y - camera.y, drill.position.z - camera.z);
			poseStack.rotate(facing(forward));
			poseStack.rotate(Axis.ZP.rotationDegrees((float)drill.spin));
			poseStack.scale(units, units, units);
			int light = LightCoordsUtil.getLightCoords(minecraft.level, BlockPos.containing(drill.position));
			model.submit(poseStack, context.submitNodeCollector(), light, model.boneMatrices(model.rest(), new Quaternionf()));
			if (beeper != null && !beeper.sprites().isEmpty() && drill.beeperScale > 0.01) {
				UltraModel.Sprite sprite = beeper.sprites().getFirst();
				float size = (float)drill.beeperScale;
				poseStack.scale(size, size, size);
				float[] c = sprite.color();
				quad(context, RenderTypes.beaconBeam(sprite.texture(), true), sprite.rect(), c[0], c[1], c[2], c[3]);
			}
			poseStack.popPose();
		}
	}

	/** The nails: each one's trail, and its model pointing the way it does. */
	void renderNails(LevelRenderContext context, List<Nail> nails) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || nails.isEmpty()) {
			return;
		}
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.lightning(), (pose, buffer) -> {
			for (Nail nail : nails) {
				sawTrail(pose, buffer, nail.position, nail.trail, camera, nail.script.getAsJsonObject("trail"));
			}
		});
		PoseStack poseStack = context.poseStack();
		float units = (float)PlayerShape.BLOCKS_PER_UNIT;
		for (Nail nail : nails) {
			UltraModel model = UltraModel.get(nail.model);
			if (model == null) {
				continue;
			}
			poseStack.pushPose();
			poseStack.translate(nail.position.x - camera.x, nail.position.y - camera.y, nail.position.z - camera.z);
			poseStack.rotate(facing(nail.forward));
			poseStack.scale(units, units, units);
			if (nail.zapped) {
				Identifier zapTexture = Identifier.parse(nail.script.get("zapTexture").getAsString());
				Matrix4f[] world = model.boneMatrices(model.rest(), new Quaternionf());
				for (int i = 0; i < model.meshCount(); i++) {
					model.submitMaterial(poseStack, context.submitNodeCollector(), world, model.meshMaterialName(i), texture -> RenderTypes.entityTranslucentEmissive(zapTexture),
						0xFFFFFFFF, LightCoordsUtil.FULL_BRIGHT);
				}
			} else {
				model.submit(poseStack, context.submitNodeCollector(), LightCoordsUtil.getLightCoords(minecraft.level, BlockPos.containing(nail.position)));
			}
			poseStack.popPose();
		}
	}

	/** The magnets: each one's trail and model, and its TimeBomb's beeper flashing on it in the color its load gives it. */
	void renderMagnets(LevelRenderContext context, List<Magnet> magnets) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || magnets.isEmpty()) {
			return;
		}
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.lightning(), (pose, buffer) -> {
			for (Magnet magnet : magnets) {
				sawTrail(pose, buffer, magnet.position, magnet.trail, camera, magnet.model.script("harpoon").getAsJsonObject("trail"));
			}
		});
		UltraModel beeper = UltraModel.get("magnet_beeper");
		PoseStack poseStack = context.poseStack();
		float units = (float)PlayerShape.BLOCKS_PER_UNIT;
		for (Magnet magnet : magnets) {
			poseStack.pushPose();
			poseStack.translate(magnet.position.x - camera.x, magnet.position.y - camera.y, magnet.position.z - camera.z);
			poseStack.rotate(facing(magnet.forward));
			poseStack.scale(units, units, units);
			magnet.model.submit(poseStack, context.submitNodeCollector(), LightCoordsUtil.getLightCoords(minecraft.level, BlockPos.containing(magnet.position)));
			if (beeper != null && !beeper.sprites().isEmpty() && magnet.beeperScale > 0.01) {
				UltraModel.Sprite sprite = beeper.sprites().getFirst();
				float size = (float)magnet.beeperScale;
				poseStack.scale(size, size, size);
				float[] c = magnet.beeperColor;
				quad(context, RenderTypes.beaconBeam(sprite.texture(), true), sprite.rect(), c[0], c[1], c[2], c[3]);
			}
			poseStack.popPose();
		}
	}

	/**
	 * Each rocket: its trail; its model, its unlit stripes in the level-up's material once it's levelled up; its glow
	 * at its tail, a camera-facing flash that blinks out and back 25 times a second (SpriteController, blinking at
	 * 50 a second), adding its color; and while it's frozen, its FreezeEffect, facing the camera and turning, in the
	 * variant's color (SpriteGetVariationColor).
	 */
	void renderRockets(LevelRenderContext context, List<Rocket> rockets) {
		Minecraft minecraft = Minecraft.getInstance();
		UltraModel model = UltraModel.get("rocket");
		if (minecraft.level == null || model == null || rockets.isEmpty()) {
			return;
		}
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		JsonObject grenade = model.script("grenade");
		context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.lightning(), (pose, buffer) -> {
			for (Rocket rocket : rockets) {
				sawTrail(pose, buffer, rocket.position, rocket.trail, camera, grenade.getAsJsonObject("trail"));
			}
		});
		Set<String> unlit = new TreeSet<>();
		for (int part = 0; part < model.meshCount(); part++) {
			if (model.meshMaterialName(part).contains("Unlit")) {
				unlit.add(model.meshMaterialName(part));
			}
		}
		JsonObject levelUp = grenade.has("levelUpMaterials") ? grenade.getAsJsonObject("levelUpMaterials") : new JsonObject();
		Matrix4f[] world = model.boneMatrices(model.rest(), new Quaternionf());
		int freezeEffect = grenade.get("freezeEffect").getAsInt();
		PoseStack poseStack = context.poseStack();
		float units = (float)PlayerShape.BLOCKS_PER_UNIT;
		Quaternionf cameraRotation = minecraft.gameRenderer.mainCamera().rotation();
		for (Rocket rocket : rockets) {
			Quaternionf facing = facing(rocket.forward);
			poseStack.pushPose();
			poseStack.translate(rocket.position.x - camera.x, rocket.position.y - camera.y, rocket.position.z - camera.z);
			poseStack.pushPose();
			poseStack.rotate(facing);
			poseStack.scale(units, units, units);
			int light = LightCoordsUtil.getLightCoords(minecraft.level, BlockPos.containing(rocket.position));
			model.submit(poseStack, context.submitNodeCollector(), light, world, unlit);
			for (String material : unlit) {
				Identifier swapped = rocket.levelledUp && levelUp.has(material) ? Identifier.parse(levelUp.getAsJsonObject(material).get("texture").getAsString()) : null;
				model.submitMaterial(poseStack, context.submitNodeCollector(), world, material,
					texture -> RenderTypes.entityCutout(swapped != null ? swapped : texture), 0xFFFFFFFF, LightCoordsUtil.FULL_BRIGHT);
			}
			poseStack.popPose();
			for (UltraModel.Sprite sprite : model.sprites()) {
				boolean frozenPart = descends(model, sprite.bone(), freezeEffect);
				if (frozenPart && !rocket.frozen) {
					continue;
				}
				// The camera-facing object the sprite is on (or under), and the sprite's place below it.
				int billboard = sprite.bone();
				while (billboard >= 0 && !facesCamera(model, billboard)) {
					billboard = model.parent(billboard);
				}
				if (billboard < 0) {
					continue;
				}
				Vector3f at = facing.transform(world[billboard].getTranslation(new Vector3f()));
				float size = world[billboard].getColumn(0, new Vector3f()).length() * units;
				poseStack.pushPose();
				poseStack.translate(at.x * units, at.y * units, at.z * units);
				poseStack.rotate(cameraRotation);
				poseStack.scale(size, size, size);
				poseStack.mulPose(below(model, billboard, sprite.bone(), rocket.age()));
				float[] c = sprite.color();
				if (frozenPart) {
					float[] variation = spriteVariationColor(model, billboard);
					quad(context, sprite.texture(), sprite.rect(), variation[0], variation[1], variation[2], c[3]);
				} else {
					float blink = (float)Math.abs(1 - (rocket.age() * spriteController(model, sprite.bone(), "fadeSpeed")) % 2);
					quad(context, RenderTypes.energySwirl(sprite.texture(), 0, 0), sprite.rect(), c[0] * blink, c[1] * blink, c[2] * blink, 1);
				}
				poseStack.popPose();
			}
			poseStack.popPose();
		}
	}

	/**
	 * Each cannonball: its trail; its model; and its glow, facing the camera and spinning (Spin), drawn as the
	 * explosions' sprites are.
	 */
	void renderCannonballs(LevelRenderContext context, List<Cannonball> cannonballs) {
		Minecraft minecraft = Minecraft.getInstance();
		UltraModel model = UltraModel.get("cannonball");
		if (minecraft.level == null || model == null || cannonballs.isEmpty()) {
			return;
		}
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		JsonObject trail = model.script("cannonball").getAsJsonObject("trail");
		context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.lightning(), (pose, buffer) -> {
			for (Cannonball ball : cannonballs) {
				sawTrail(pose, buffer, ball.position, ball.trail, camera, trail);
			}
		});
		Matrix4f[] world = model.boneMatrices(model.rest(), new Quaternionf());
		PoseStack poseStack = context.poseStack();
		float units = (float)PlayerShape.BLOCKS_PER_UNIT;
		Quaternionf cameraRotation = minecraft.gameRenderer.mainCamera().rotation();
		for (Cannonball ball : cannonballs) {
			Quaternionf facing = facing(ball.forward);
			poseStack.pushPose();
			poseStack.translate(ball.position.x - camera.x, ball.position.y - camera.y, ball.position.z - camera.z);
			poseStack.pushPose();
			poseStack.rotate(facing);
			poseStack.scale(units, units, units);
			model.submit(poseStack, context.submitNodeCollector(), LightCoordsUtil.getLightCoords(minecraft.level, BlockPos.containing(ball.position)), world);
			poseStack.popPose();
			for (UltraModel.Sprite sprite : model.sprites()) {
				int billboard = sprite.bone();
				while (billboard >= 0 && !facesCamera(model, billboard)) {
					billboard = model.parent(billboard);
				}
				if (billboard < 0) {
					continue;
				}
				Vector3f at = facing.transform(world[billboard].getTranslation(new Vector3f()));
				float size = world[billboard].getColumn(0, new Vector3f()).length() * units;
				poseStack.pushPose();
				poseStack.translate(at.x * units, at.y * units, at.z * units);
				poseStack.rotate(cameraRotation);
				poseStack.scale(size, size, size);
				poseStack.mulPose(below(model, billboard, sprite.bone(), ball.age()));
				float[] c = sprite.color();
				quad(context, sprite.texture(), sprite.rect(), c[0], c[1], c[2], c[3]);
				poseStack.popPose();
			}
			poseStack.popPose();
		}
	}

	private static boolean facesCamera(UltraModel model, int bone) {
		for (JsonElement element : model.scripts("alwaysLookAtCamera")) {
			if (element.getAsJsonObject().get("bone").getAsInt() == bone) {
				return true;
			}
		}
		return false;
	}

	/** Where {@code bone} is under its ancestor {@code top}, in the ancestor's space: each Spin between them turned by now. */
	private static Matrix4f below(UltraModel model, int top, int bone, double age) {
		Matrix4f matrix = new Matrix4f();
		Pose rest = model.rest();
		for (int b = bone; b != top; b = model.parent(b)) {
			Quaternionf rotation = new Quaternionf(rest.qx(b), rest.qy(b), rest.qz(b), rest.qw(b));
			for (JsonElement element : model.scripts("spins")) {
				JsonObject spin = element.getAsJsonObject();
				if (spin.get("bone").getAsInt() == b) {
					JsonArray axis = spin.getAsJsonArray("axis");
					rotation.rotateAxis((float)Math.toRadians(spin.get("speed").getAsDouble() * age % 360), axis.get(0).getAsFloat(), axis.get(1).getAsFloat(), axis.get(2).getAsFloat());
				}
			}
			matrix.mulLocal(new Matrix4f().translationRotateScale(rest.px(b), rest.py(b), rest.pz(b), rotation.x, rotation.y, rotation.z, rotation.w, rest.sx(b), rest.sy(b), rest.sz(b)));
		}
		return matrix;
	}

	/** SpriteGetVariationColor on {@code bone}: the variant's color its sprites take. */
	private static float[] spriteVariationColor(UltraModel model, int bone) {
		for (JsonElement element : model.scripts("spriteVariationColors")) {
			if (element.getAsJsonObject().get("bone").getAsInt() == bone) {
				JsonArray color = element.getAsJsonObject().getAsJsonArray("variationColor");
				return new float[]{color.get(0).getAsFloat(), color.get(1).getAsFloat(), color.get(2).getAsFloat()};
			}
		}
		return new float[]{1, 1, 1};
	}

	/**
	 * The zapper: its model, its prongs open or (attached) closed; its cable from where it starts, in its color; and
	 * once attached, its pulse orb on the cable with its line back to the cable's start.
	 */
	void renderZapper(LevelRenderContext context, Zapper zapper) {
		Minecraft minecraft = Minecraft.getInstance();
		UltraModel model = UltraModel.get("zapper");
		if (minecraft.level == null || model == null || zapper.removed) {
			return;
		}
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		JsonObject script = model.script("zapper");
		PoseStack poseStack = context.poseStack();
		float units = (float)PlayerShape.BLOCKS_PER_UNIT;
		int light = LightCoordsUtil.getLightCoords(minecraft.level, BlockPos.containing(zapper.position));
		int hidden = script.get(zapper.attached ? "openProngs" : "closedProngs").getAsInt();
		int orb = script.get("orb").getAsInt();
		poseStack.pushPose();
		poseStack.translate(zapper.position.x - camera.x, zapper.position.y - camera.y, zapper.position.z - camera.z);
		poseStack.rotate(facing(zapper.forward));
		poseStack.scale(units, units, units);
		Matrix4f[] world = model.boneMatrices(model.rest(), new Quaternionf());
		model.submitGlowing(poseStack, context.submitNodeCollector(), light, world, bone -> !descends(model, bone, hidden) && !descends(model, bone, orb), object -> null);
		poseStack.popPose();

		JsonObject line = script.getAsJsonObject("line");
		Identifier texture = Identifier.parse(line.getAsJsonObject("material").get("texture").getAsString());
		double width = line.get("width").getAsDouble() * PlayerShape.BLOCKS_PER_UNIT;
		float[] color = zapper.cableColor();
		Vec3 from = zapper.lineStart.subtract(camera);
		Vec3 to = zapper.position.subtract(camera);
		int startLight = LightCoordsUtil.getLightCoords(minecraft.level, BlockPos.containing(zapper.lineStart));
		context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.entityCutout(texture), (pose, buffer) -> {
			Vec3 side = to.subtract(from).cross(from.add(to).scale(0.5)).normalize().scale(width / 2);
			if (!Double.isFinite(side.x)) {
				return;
			}
			Vec3[] corners = {from.add(side), from.subtract(side), to.subtract(side), to.add(side)};
			float[][] uvs = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
			int[] lights = {startLight, startLight, light, light};
			for (int k = 0; k < 4; k++) {
				cableVertex(pose, buffer, corners[k], uvs[k], color, lights[k]);
			}
			for (int k = 3; k >= 0; k--) {
				cableVertex(pose, buffer, corners[k], uvs[k], color, lights[k]);
			}
		});

		if (!zapper.attached) {
			return;
		}
		Vec3 pulse = zapper.pulsePosition();
		if (zapper.orb != null) {
			zapper.orb.render(context, camera);
		}
		UltraModel.Quad quad = model.quads().isEmpty() ? null : model.quads().getFirst();
		if (quad != null && quad.texture() != null) {
			float size = (float)(model.rest().sx(orb) * PlayerShape.BLOCKS_PER_UNIT);
			poseStack.pushPose();
			poseStack.translate(pulse.x - camera.x, pulse.y - camera.y, pulse.z - camera.z);
			poseStack.rotate(facing(zapper.forward));
			poseStack.scale(size, size, size);
			quad(context, RenderTypes.beaconBeam(quad.texture(), true), new float[]{-0.5F, -0.5F, 0.5F, 0.5F}, 1, 1, 1, 1);
			poseStack.popPose();
		}
		JsonObject orbLine = script.getAsJsonObject("orbLine");
		JsonObject material = orbLine.getAsJsonObject("material");
		float tint = material.getAsJsonObject("colors").has("_TintColor") ? 2 * material.getAsJsonObject("colors").getAsJsonArray("_TintColor").get(0).getAsFloat() : 1;
		double orbWidth = orbLine.get("width").getAsDouble() * PlayerShape.BLOCKS_PER_UNIT;
		JsonArray curve = orbLine.getAsJsonArray("widthCurve");
		JsonObject gradient = orbLine.getAsJsonObject("color");
		Vec3 head = pulse.subtract(camera);
		Vec3 tail = from;
		context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, buffer) -> {
			Vec3 along = tail.subtract(head);
			Vec3 facing = along.cross(head.add(tail).scale(0.5)).normalize();
			if (!Double.isFinite(facing.x)) {
				return;
			}
			Vec3 headSide = facing.scale(orbWidth * curve(curve, 0) / 2);
			Vec3 tailSide = facing.scale(orbWidth * curve(curve, 1) / 2);
			float[] a = Particles.gradient(gradient, 0);
			float[] b = Particles.gradient(gradient, 1);
			Vec3[] corners = {head.add(headSide), head.subtract(headSide), tail.subtract(tailSide), tail.add(tailSide)};
			float[][] colors = {a, a, b, b};
			for (int k = 0; k < 4; k++) {
				buffer.addVertex(pose, (float)corners[k].x, (float)corners[k].y, (float)corners[k].z).setColor(colors[k][0] * tint, colors[k][1] * tint, colors[k][2] * tint, colors[k][3]);
			}
			for (int k = 3; k >= 0; k--) {
				buffer.addVertex(pose, (float)corners[k].x, (float)corners[k].y, (float)corners[k].z).setColor(colors[k][0] * tint, colors[k][1] * tint, colors[k][2] * tint, colors[k][3]);
			}
		});
	}

	private static void cableVertex(PoseStack.Pose pose, VertexConsumer buffer, Vec3 at, float[] uv, float[] color, int light) {
		buffer.addVertex(pose, (float)at.x, (float)at.y, (float)at.z).setColor(color[0], color[1], color[2], color[3]).setUv(uv[0], uv[1])
			.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0, 1, 0);
	}

	/** Whether {@code bone} is {@code ancestor} or under it. */
	private static boolean descends(UltraModel model, int bone, int ancestor) {
		for (int b = bone; b >= 0; b = model.parent(b)) {
			if (b == ancestor) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The rotation that turns a prefab (mirrored into Minecraft's axes, so its forward is -z) to face along
	 * {@code direction}, keeping its up as near the world's as it can. The conjugated look rotation takes +z along
	 * the direction it's given.
	 */
	static Quaternionf facing(Vec3 direction) {
		Vec3 back = direction.normalize().scale(-1);
		Vector3f up = Math.abs(back.y) > 0.999 ? new Vector3f(0, 0, 1) : new Vector3f(0, 1, 0);
		return new Quaternionf().lookAlong((float)back.x, (float)back.y, (float)back.z, up.x, up.y, up.z).conjugate();
	}

	/** A saw's model at {@code position} facing {@code forward}, its blade's texture scrolled, and its glint (if it has one, at {@code glint} of its size). */
	private static void submitSaw(LevelRenderContext context, UltraModel model, Vec3 position, Vec3 forward, double scroll, Vec3 camera, boolean showModel, float glint, double age) {
		PoseStack poseStack = context.poseStack();
		float units = (float)PlayerShape.BLOCKS_PER_UNIT;
		int light = LightCoordsUtil.getLightCoords(Minecraft.getInstance().level, BlockPos.containing(position));
		poseStack.pushPose();
		poseStack.translate(position.x - camera.x, position.y - camera.y, position.z - camera.z);
		poseStack.rotate(facing(forward));
		poseStack.scale(units, units, units);
		Matrix4f[] world = model.boneMatrices(model.rest(), new Quaternionf());
		if (showModel) {
			model.submit(poseStack, context.submitNodeCollector(), light, world, Set.of(SAW_BLADE));
			model.submitMaterial(poseStack, context.submitNodeCollector(), world, SAW_BLADE, RenderTypes::entityCutout, 0xFFFFFFFF, light, (float)scroll);
		}
		if (glint > 0 && !model.sprites().isEmpty()) {
			UltraModel.Sprite sprite = model.sprites().getFirst();
			poseStack.mulPose(world[sprite.bone()]);
			poseStack.rotate(Axis.ZP.rotationDegrees((float)(age * SAW_GLINT_SPIN % 360)));
			poseStack.scale(glint, glint, glint);
			float[] c = sprite.color();
			quad(context, RenderTypes.text(sprite.texture()), sprite.rect(), c[0], c[1], c[2], c[3]);
		}
		poseStack.popPose();
	}

	/**
	 * A saw's TrailRenderer: from it back along its points, narrowing as its width curve says, colored by its
	 * gradient and its Legacy Particles/Additive material (twice its tint).
	 */
	private static void sawTrail(PoseStack.Pose pose, VertexConsumer buffer, Vec3 head, Iterable<Pellet.TrailPoint> trail, Vec3 camera, JsonObject settings) {
		JsonObject material = settings.getAsJsonObject("material");
		float tint = material.getAsJsonObject("colors").has("_TintColor") ? 2 * material.getAsJsonObject("colors").getAsJsonArray("_TintColor").get(0).getAsFloat() : 1;
		sawTrail(pose, buffer, head, trail, camera, settings, new float[]{tint, tint, tint});
	}

	/** An extracted TrailRenderer, its colors times {@code tint}. */
	private static void sawTrail(PoseStack.Pose pose, VertexConsumer buffer, Vec3 head, Iterable<Pellet.TrailPoint> trail, Vec3 camera, JsonObject settings, float[] tint) {
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
		double width = settings.get("width").getAsDouble() * PlayerShape.BLOCKS_PER_UNIT;
		JsonArray curve = settings.getAsJsonArray("widthCurve");
		Vec3[] left = new Vec3[points.size()];
		Vec3[] right = new Vec3[points.size()];
		float[][] colors = new float[points.size()][];
		for (int j = 0; j < points.size(); j++) {
			double t = along[j] / length;
			Vec3 tangent = points.get(Math.min(j + 1, points.size() - 1)).subtract(points.get(Math.max(j - 1, 0)));
			Vec3 side = tangent.cross(points.get(j)).normalize().scale(width * curve(curve, t) / 2);
			if (!Double.isFinite(side.x)) {
				side = Vec3.ZERO;
			}
			left[j] = points.get(j).add(side);
			right[j] = points.get(j).subtract(side);
			float[] c = Particles.gradient(settings.getAsJsonObject("color"), t);
			colors[j] = new float[]{c[0] * tint[0], c[1] * tint[1], c[2] * tint[2], c[3]};
		}
		for (int i = 0; i + 1 < points.size(); i++) {
			Vec3[] quad = {left[i], right[i], right[i + 1], left[i + 1]};
			float[][] quadColors = {colors[i], colors[i], colors[i + 1], colors[i + 1]};
			// Both faces: the additive type culls back faces.
			for (int k = 0; k < 4; k++) {
				buffer.addVertex(pose, new Vector3f((float)quad[k].x, (float)quad[k].y, (float)quad[k].z)).setColor(quadColors[k][0], quadColors[k][1], quadColors[k][2], quadColors[k][3]);
			}
			for (int k = 3; k >= 0; k--) {
				buffer.addVertex(pose, new Vector3f((float)quad[k].x, (float)quad[k].y, (float)quad[k].z)).setColor(quadColors[k][0], quadColors[k][1], quadColors[k][2], quadColors[k][3]);
			}
		}
	}

	/** A width curve's value at {@code t}, between its keys. */
	private static double curve(JsonArray keys, double t) {
		JsonArray previous = keys.get(0).getAsJsonArray();
		if (t <= previous.get(0).getAsDouble()) {
			return previous.get(1).getAsDouble();
		}
		for (int i = 1; i < keys.size(); i++) {
			JsonArray key = keys.get(i).getAsJsonArray();
			if (t <= key.get(0).getAsDouble()) {
				double t0 = previous.get(0).getAsDouble();
				double t1 = key.get(0).getAsDouble();
				double share = t1 > t0 ? (t - t0) / (t1 - t0) : 1;
				return previous.get(1).getAsDouble() + (key.get(1).getAsDouble() - previous.get(1).getAsDouble()) * share;
			}
			previous = key;
		}
		return previous.get(1).getAsDouble();
	}

	/** The chain: a flat strip from {@code from} to {@code to}, turned to the camera, its texture stretched along it. */
	private static void chain(PoseStack.Pose pose, VertexConsumer buffer, Vec3 from, Vec3 to, double width) {
		Vec3 side = to.subtract(from).cross(from.add(to).scale(0.5)).normalize().scale(width / 2);
		if (!Double.isFinite(side.x)) {
			return;
		}
		Vec3[] corners = {from.add(side), from.subtract(side), to.subtract(side), to.add(side)};
		float[][] uvs = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
		for (int k = 0; k < 4; k++) {
			chainVertex(pose, buffer, corners[k], uvs[k]);
		}
		for (int k = 3; k >= 0; k--) {
			chainVertex(pose, buffer, corners[k], uvs[k]);
		}
	}

	private static void chainVertex(PoseStack.Pose pose, VertexConsumer buffer, Vec3 at, float[] uv) {
		buffer.addVertex(pose, (float)at.x, (float)at.y, (float)at.z).setColor(-1).setUv(uv[0], uv[1])
			.setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
	}

	/**
	 * The Whiplash's wire (HookArm's LineRenderer): its points from the hand to the hook, the ones between wobbling
	 * along the camera's up while it's thrown, as a strip facing the camera. Its texture repeats a unit at a time,
	 * stretching and running along as the wire gets longer.
	 */
	void renderWire(LevelRenderContext context, JsonObject line, Vec3 hand, Vec3 hook, Vec3 up, double warp) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			return;
		}
		double s = PlayerShape.BLOCKS_PER_UNIT;
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		int count = line.get("positions").getAsInt();
		double width = line.get("width").getAsDouble() * s;
		Identifier texture = Identifier.parse(line.getAsJsonObject("material").get("texture").getAsString());
		Vec3[] points = new Vec3[count];
		for (int i = 0; i < count; i++) {
			double wobble = i == 0 || i == count - 1 ? 0 : (i % 2 == 0 ? -3 : 3) / (double)i * warp;
			Vec3 point = i == count - 1 ? hook : hand.lerp(hook, i / (double)count);
			points[i] = point.add(up.scale(wobble * s));
		}
		double length = hand.distanceTo(hook) / s;
		double share = length / WIRE_LENGTH;
		double[] u = new double[count];
		int[] light = new int[count];
		for (int i = 0; i < count; i++) {
			u[i] = (i == 0 ? 0 : u[i - 1] + points[i].distanceTo(points[i - 1]) / s * (share + 1)) ;
			light[i] = LightCoordsUtil.getLightCoords(minecraft.level, BlockPos.containing(points[i]));
		}
		context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.entityCutout(texture), (pose, buffer) -> {
			Vec3[] left = new Vec3[count];
			Vec3[] right = new Vec3[count];
			for (int i = 0; i < count; i++) {
				Vec3 at = points[i].subtract(camera);
				Vec3 tangent = points[Math.min(i + 1, count - 1)].subtract(points[Math.max(i - 1, 0)]);
				Vec3 side = tangent.cross(at).normalize().scale(width / 2);
				if (!Double.isFinite(side.x)) {
					side = Vec3.ZERO;
				}
				left[i] = at.add(side);
				right[i] = at.subtract(side);
			}
			for (int i = 0; i + 1 < count; i++) {
				Vec3[] corners = {left[i], right[i], right[i + 1], left[i + 1]};
				float u0 = (float)(u[i] - share * 10);
				float u1 = (float)(u[i + 1] - share * 10);
				float[][] uvs = {{u0, 0}, {u0, 1}, {u1, 1}, {u1, 0}};
				int[] lights = {light[i], light[i], light[i + 1], light[i + 1]};
				for (int k = 0; k < 4; k++) {
					wireVertex(pose, buffer, corners[k], uvs[k], lights[k]);
				}
				for (int k = 3; k >= 0; k--) {
					wireVertex(pose, buffer, corners[k], uvs[k], lights[k]);
				}
			}
		});
	}

	private static void wireVertex(PoseStack.Pose pose, VertexConsumer buffer, Vec3 at, float[] uv, int light) {
		buffer.addVertex(pose, (float)at.x, (float)at.y, (float)at.z).setColor(-1).setUv(uv[0], uv[1])
			.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0, 1, 0);
	}

	/**
	 * Each power slam shockwave as its prefab draws it, stretched to its width: its ring adding its texture and
	 * the flat disc under it, both fading once it's done.
	 */
	void renderShockwaves(LevelRenderContext context, List<Shockwave> shockwaves) {
		if (shockwaves.isEmpty()) {
			return;
		}
		Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
		PoseStack poseStack = context.poseStack();
		float units = (float)PlayerShape.BLOCKS_PER_UNIT;
		for (Shockwave shockwave : shockwaves) {
			UltraModel model = shockwave.model;
			Pose pose = model.rest().copy();
			float width = (float)shockwave.scale();
			pose.setScale(0, width, pose.sy(0), width);
			Matrix4f[] world = model.boneMatrices(pose, new Quaternionf());
			poseStack.pushPose();
			poseStack.translate(shockwave.origin.x - camera.x, shockwave.origin.y - camera.y, shockwave.origin.z - camera.z);
			poseStack.scale(units, units, units);
			float ring = (float)Math.max(1 - shockwave.faded(), 0);
			if (ring > 0) {
				int glow = 0xFF000000 | channel(ring) << 16 | channel(ring) << 8 | channel(ring);
				for (int i = 0; i < model.meshCount(); i++) {
					model.submitMaterial(poseStack, context.submitNodeCollector(), world, model.meshMaterialName(i), texture -> RenderTypes.energySwirl(texture, 0, 0), glow, LightCoordsUtil.FULL_BRIGHT);
				}
			}
			for (UltraModel.Sprite sprite : model.sprites()) {
				float[] c = sprite.color();
				float alpha = (float)(c[3] - shockwave.faded());
				if (alpha <= 0) {
					continue;
				}
				poseStack.pushPose();
				poseStack.mulPose(world[sprite.bone()]);
				quad(context, sprite.texture(), sprite.rect(), c[0], c[1], c[2], alpha);
				poseStack.popPose();
			}
			poseStack.popPose();
		}
	}

	private static int channel(float value) {
		return Math.round(Math.clamp(value, 0, 1) * 255);
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
				ball(pose, buffer, pellet.position.subtract(camera), PELLET_RADIUS * PlayerShape.BLOCKS_PER_UNIT, facing, color);
			}
		});
	}

	/** A ball, as a disc facing the camera: an unlit sphere looks the same from every side. */
	private static void ball(PoseStack.Pose pose, VertexConsumer buffer, Vec3 at, double radius, Quaternionf facing, float[] color) {
		int sides = 8;
		float r = (float)radius;
		for (int i = 0; i < sides; i++) {
			double a0 = Math.PI * 2 * i / sides;
			double a1 = Math.PI * 2 * (i + 1) / sides;
			Vector3f p0 = facing.transform(new Vector3f((float)Math.cos(a0) * r, (float)Math.sin(a0) * r, 0));
			Vector3f p1 = facing.transform(new Vector3f((float)Math.cos(a1) * r, (float)Math.sin(a1) * r, 0));
			Vector3f centre = new Vector3f((float)at.x, (float)at.y, (float)at.z);
			for (Vector3f v : new Vector3f[]{new Vector3f(centre), new Vector3f(centre).add(p0), new Vector3f(centre).add(p1), new Vector3f(centre)}) {
				buffer.addVertex(pose, v).setColor(color[0], color[1], color[2], 1);
			}
		}
	}

	/**
	 * The Firestarter's drops of gasoline: each one's trail and its ball in the gasoline's color (the unlit color of
	 * its material, which its trail's colors are times).
	 */
	void renderGasoline(LevelRenderContext context, List<GasolineProjectile> drops) {
		UltraModel model = UltraModel.get(GasolineProjectile.MODEL);
		if (model == null || drops.isEmpty()) {
			return;
		}
		JsonObject script = model.script("gasolineProjectile");
		JsonObject trail = script.getAsJsonObject("trail");
		float[] color = floats(trail.getAsJsonObject("material").getAsJsonObject("colors").getAsJsonArray("_Color"));
		double radius = script.get("ballRadius").getAsDouble() * PlayerShape.BLOCKS_PER_UNIT;
		Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
		Quaternionf facing = Minecraft.getInstance().gameRenderer.mainCamera().rotation();
		context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.debugQuads(), (pose, buffer) -> {
			for (GasolineProjectile drop : drops) {
				sawTrail(pose, buffer, drop.position, drop.trail, camera, trail, color);
				ball(pose, buffer, drop.position.subtract(camera), radius, facing, color);
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

	/** The beam's width multiplier: 1, narrowing to nothing as it fades. */
	private float beamWidth(Beam beam) {
		return (float)Math.clamp(1 - (this.clock - beam.fadeStart) / beam.style.life, 0, 1);
	}

	/**
	 * The beam prefab's other LineRenderers (CopyLineRenderer): along the same points, each at its own width times
	 * the beam's multiplier, its texture stretched along it or repeated by the length (both times its texture scale),
	 * shaded by its colors. The one with an ElectricityLine shows the beam's current arc pick instead.
	 */
	private static void renderLines(LevelRenderContext context, Beam beam, Vec3 camera, float multiplier) {
		UltraModel model = beam.style.prefab == null ? null : UltraModel.get(beam.style.prefab);
		JsonObject script = model == null ? null : model.script("beam");
		if (script == null || multiplier <= 0) {
			return;
		}
		Vec3 from = beam.from.subtract(camera);
		Vec3 to = beam.to.subtract(camera);
		Vec3 along = to.subtract(from);
		double units = along.length() / PlayerShape.BLOCKS_PER_UNIT;
		Vec3 facing = along.cross(from.add(to).scale(0.5)).normalize();
		JsonArray lines = script.getAsJsonArray("lines");
		for (int l = 1; l < lines.size(); l++) {
			JsonObject line = lines.get(l).getAsJsonObject();
			JsonObject material = line.getAsJsonObject("material");
			JsonObject electricity = line.has("electricity") ? line.getAsJsonObject("electricity") : null;
			Identifier texture = Identifier.parse(electricity != null
				? electricity.getAsJsonArray("frames").get(beam.arcFrame).getAsString()
				: material.get("texture").getAsString());
			float[] tint = material.getAsJsonObject("colors").has("_Color") ? floats(material.getAsJsonObject("colors").getAsJsonArray("_Color")) : new float[]{1, 1, 1, 1};
			JsonArray textureScale = line.getAsJsonArray("textureScale");
			float uScale = textureScale.get(0).getAsFloat();
			float vScale = textureScale.get(1).getAsFloat();
			boolean tile = line.get("textureMode").getAsInt() == 1;
			double width = electricity != null ? beam.arcWidth : line.get("width").getAsDouble();
			Vec3 side = facing.scale(width * multiplier * PlayerShape.BLOCKS_PER_UNIT / 2);
			JsonObject gradient = line.getAsJsonObject("color");
			double[] times = electricity != null ? new double[]{0, 1} : gradientTimes(gradient);
			context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.beaconBeam(texture, true), (pose, buffer) -> {
				for (int i = 0; i + 1 < times.length; i++) {
					float[] a = electricity != null ? beam.arcStart : gradientAt(gradient, times[i]);
					float[] b = electricity != null ? beam.arcEnd : gradientAt(gradient, times[i + 1]);
					float ua = (float)((tile ? times[i] * units : times[i]) * uScale);
					float ub = (float)((tile ? times[i + 1] * units : times[i + 1]) * uScale);
					Vec3 pa = from.add(along.scale(times[i]));
					Vec3 pb = from.add(along.scale(times[i + 1]));
					// Both faces: the strip is seen from either side of its winding.
					lineCorner(pose, buffer, pa.add(side), ua, 0, a, tint);
					lineCorner(pose, buffer, pa.subtract(side), ua, vScale, a, tint);
					lineCorner(pose, buffer, pb.subtract(side), ub, vScale, b, tint);
					lineCorner(pose, buffer, pb.add(side), ub, 0, b, tint);
					lineCorner(pose, buffer, pb.add(side), ub, 0, b, tint);
					lineCorner(pose, buffer, pb.subtract(side), ub, vScale, b, tint);
					lineCorner(pose, buffer, pa.subtract(side), ua, vScale, a, tint);
					lineCorner(pose, buffer, pa.add(side), ua, 0, a, tint);
				}
			});
		}
	}

	private static void lineCorner(PoseStack.Pose pose, VertexConsumer buffer, Vec3 at, float u, float v, float[] color, float[] tint) {
		buffer.addVertex(pose, new Vector3f((float)at.x, (float)at.y, (float)at.z))
			.setColor(color[0] * tint[0], color[1] * tint[1], color[2] * tint[2], color[3] * tint[3])
			.setUv(u, v)
			.setLight(LightCoordsUtil.FULL_BRIGHT)
			.setNormal(pose, 0, 0, 1);
	}

	/** Where a gradient's color and alpha keys are, in order, from 0 to 1. */
	private static double[] gradientTimes(JsonObject gradient) {
		TreeSet<Double> times = new TreeSet<>(List.of(0.0, 1.0));
		for (String keys : new String[]{"colors", "alphas"}) {
			for (JsonElement key : gradient.getAsJsonArray(keys)) {
				times.add(key.getAsJsonArray().get(0).getAsDouble());
			}
		}
		return times.stream().mapToDouble(Double::doubleValue).toArray();
	}

	/** A gradient's color and alpha at {@code t}, blended between its keys. */
	private static float[] gradientAt(JsonObject gradient, double t) {
		float[] rgb = sample(gradient.getAsJsonArray("colors"), t, 3);
		float[] alpha = sample(gradient.getAsJsonArray("alphas"), t, 1);
		return new float[]{rgb[0], rgb[1], rgb[2], alpha[0]};
	}

	private static float[] sample(JsonArray keys, double t, int channels) {
		JsonArray previous = keys.get(0).getAsJsonArray();
		for (JsonElement element : keys) {
			JsonArray key = element.getAsJsonArray();
			double time = key.get(0).getAsDouble();
			if (time >= t) {
				double start = previous.get(0).getAsDouble();
				double blend = time > start ? Math.clamp((t - start) / (time - start), 0, 1) : 1;
				float[] values = new float[channels];
				for (int c = 0; c < channels; c++) {
					values[c] = (float)(previous.get(c + 1).getAsDouble() + (key.get(c + 1).getAsDouble() - previous.get(c + 1).getAsDouble()) * blend);
				}
				return values;
			}
			previous = key;
		}
		float[] values = new float[channels];
		for (int c = 0; c < channels; c++) {
			values[c] = previous.get(c + 1).getAsFloat();
		}
		return values;
	}

	private static float[] floats(JsonArray array) {
		float[] values = new float[array.size()];
		for (int i = 0; i < values.length; i++) {
			values[i] = array.get(i).getAsFloat();
		}
		return values;
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
