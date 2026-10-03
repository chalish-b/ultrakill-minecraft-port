package dev.ultracraft.client.combat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.ultracraft.PlayerShape;
import dev.ultracraft.client.render.UltraModel;
import dev.ultracraft.combat.Gore;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** Beams, blood and impacts, and drawing beams, coins and coin trails in the world. */
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
		SHARP(0.5, new float[][]{{0, 1, 1, 1}, {1, 1, 0.23529413F, 0.23529413F}});

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

	private final List<Beam> beams = new ArrayList<>();
	private final RandomSource random = RandomSource.create();
	private double clock;

	void update(double dt) {
		this.clock += dt;
		this.beams.removeIf(beam -> this.clock - beam.start > beam.style.life);
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

	void render(LevelRenderContext context, List<Coin> coins) {
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
