package dev.ultracraft.client.combat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ultracraft.PlayerShape;
import dev.ultracraft.UltraBlocks;
import dev.ultracraft.Ultracraft;
import dev.ultracraft.client.render.UltraModel;
import dev.ultracraft.combat.Gasoline;
import dev.ultracraft.combat.GasolineFireBlock;
import dev.ultracraft.combat.GasolineStain;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The gasoline stains, drawn as ULTRAKILL's decals: each splat flat on its surface, turned as it landed, in the
 * gasoline's color, and cut to the faces of the blocks under it (the stain material's ClipToSurface), so it never
 * hangs off an edge. A burning stain shrinks away as its fire goes out (BurningVoxel.SetSize). Enemies soaked in
 * gasoline darken toward its color.
 */
public final class GasolineStains {
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	/** Stains on chunks up to this many chunks from the camera's are drawn. */
	private static final int RANGE = 8;
	/** They're drawn this far off their surface (blocks), so they don't flicker into it. */
	private static final double LIFT = 0.003;
	/** The color at a stain's edges, which its texture darkens inwards: the napalm spray's own purple (its muzzle particles'). */
	private static final float[] COLOR = {0.12057281F, 0.09046815F, 0.17924529F, 1};

	private final List<Identifier> textures = new ArrayList<>();

	/**
	 * The tint of an enemy soaked in {@code fuel} of gasoline: toward the gasoline's color by how oiled it is
	 * (EnemyIdentifier's _OiledAmount, the fuel out of 5).
	 */
	public static int oilTint(float fuel) {
		float oiled = Math.clamp(fuel / Gasoline.MAX_FUEL, 0, 1);
		return ARGB.colorFromFloat(1, 1 + (COLOR[0] - 1) * oiled, 1 + (COLOR[1] - 1) * oiled, 1 + (COLOR[2] - 1) * oiled);
	}

	GasolineStains() {
		for (int i = 0; i < 5; i++) {
			this.textures.add(Ultracraft.id("textures/gasoline/stain_" + i + ".png"));
		}
	}

	void render(LevelRenderContext context) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		UltraModel model = UltraModel.get(GasolineProjectile.MODEL);
		if (level == null || model == null) {
			return;
		}
		double size = model.script("gasolineProjectile").get("stainSize").getAsDouble() * S;
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		List<List<GasolineStain>> bySplat = new ArrayList<>();
		for (int i = 0; i < this.textures.size(); i++) {
			bySplat.add(new ArrayList<>());
		}
		int cameraX = BlockPos.containing(camera).getX() >> 4;
		int cameraZ = BlockPos.containing(camera).getZ() >> 4;
		for (int cx = cameraX - RANGE; cx <= cameraX + RANGE; cx++) {
			for (int cz = cameraZ - RANGE; cz <= cameraZ + RANGE; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				List<GasolineStain> stains = chunk == null ? null : chunk.getAttached(Gasoline.STAINS);
				if (stains != null) {
					for (GasolineStain stain : stains) {
						bySplat.get(Math.floorMod(stain.splat(), bySplat.size())).add(stain);
					}
				}
			}
		}
		for (int i = 0; i < bySplat.size(); i++) {
			List<GasolineStain> stains = bySplat.get(i);
			if (stains.isEmpty()) {
				continue;
			}
			context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.entityTranslucent(this.textures.get(i), false), (pose, buffer) -> {
				for (GasolineStain stain : stains) {
					double shrink = burnedAway(level, stain);
					if (shrink > 0) {
						decal(pose, buffer, level, stain, size * shrink, camera);
					}
				}
			});
		}
	}

	/** How much of a stain is left: all of it until its fire starts going out. */
	private static double burnedAway(ClientLevel level, GasolineStain stain) {
		if (!stain.lit()) {
			return 1;
		}
		for (int out = 0; out < 2; out++) {
			BlockState fire = level.getBlockState(stain.block().relative(stain.face(), out));
			if (fire.is(UltraBlocks.GASOLINE_FIRE)) {
				return GasolineFireBlock.left(fire);
			}
		}
		return 1;
	}

	/**
	 * A stain {@code size} blocks across: its square, turned about its surface's normal, cut to each block face it
	 * lies on (the faces of the collision boxes of the blocks behind it that are flush with its surface).
	 */
	private static void decal(PoseStack.Pose pose, VertexConsumer buffer, ClientLevel level, GasolineStain stain, double size, Vec3 camera) {
		Direction face = stain.face();
		Direction.Axis normal = face.getAxis();
		Direction.Axis[] plane = plane(normal);
		Vec3 at = stain.position();
		double surface = at.get(normal);
		double x0 = at.get(plane[0]);
		double y0 = at.get(plane[1]);
		double half = size / 2;
		double cos = Math.cos(Math.toRadians(stain.angle()));
		double sin = Math.sin(Math.toRadians(stain.angle()));
		// The square's corners in the plane: x, y, u, v.
		List<double[]> square = new ArrayList<>();
		double[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
		double minX = Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		for (double[] c : corners) {
			double x = x0 + (c[0] * cos - c[1] * sin) * half;
			double y = y0 + (c[0] * sin + c[1] * cos) * half;
			square.add(new double[]{x, y, (c[0] + 1) / 2, (1 - c[1]) / 2});
			minX = Math.min(minX, x);
			minY = Math.min(minY, y);
			maxX = Math.max(maxX, x);
			maxY = Math.max(maxY, y);
		}
		int behind = (int)Math.floor(surface - face.getAxisDirection().getStep() * 1.0E-3);
		int light = LightCoordsUtil.getLightCoords(level, stain.block());
		float nx = face.getStepX();
		float ny = face.getStepY();
		float nz = face.getStepZ();
		for (int i = (int)Math.floor(minX); i <= (int)Math.floor(maxX); i++) {
			for (int j = (int)Math.floor(minY); j <= (int)Math.floor(maxY); j++) {
				BlockPos pos = position(normal, behind, plane, i, j);
				for (AABB box : level.getBlockState(pos).getCollisionShape(level, pos).toAabbs()) {
					AABB placed = box.move(pos);
					double flush = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? placed.max(normal) : placed.min(normal);
					if (Math.abs(flush - surface) > 1.0E-4) {
						continue;
					}
					List<double[]> piece = clip(square, placed.min(plane[0]), placed.max(plane[0]), placed.min(plane[1]), placed.max(plane[1]));
					for (int k = 1; k + 1 < piece.size(); k++) {
						for (double[] corner : new double[][]{piece.getFirst(), piece.get(k), piece.get(k + 1), piece.get(k + 1)}) {
							Vec3 world = world(normal, surface + face.getAxisDirection().getStep() * LIFT, plane, corner[0], corner[1]).subtract(camera);
							buffer.addVertex(pose, (float)world.x, (float)world.y, (float)world.z).setColor(COLOR[0], COLOR[1], COLOR[2], COLOR[3])
								.setUv((float)corner[2], (float)corner[3]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
						}
					}
				}
			}
		}
	}

	/** The two axes across a surface facing along {@code normal}. */
	private static Direction.Axis[] plane(Direction.Axis normal) {
		return switch (normal) {
			case X -> new Direction.Axis[]{Direction.Axis.Z, Direction.Axis.Y};
			case Y -> new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z};
			case Z -> new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Y};
		};
	}

	private static BlockPos position(Direction.Axis normal, int along, Direction.Axis[] plane, int x, int y) {
		int[] xyz = new int[3];
		xyz[normal.ordinal()] = along;
		xyz[plane[0].ordinal()] = x;
		xyz[plane[1].ordinal()] = y;
		return new BlockPos(xyz[0], xyz[1], xyz[2]);
	}

	private static Vec3 world(Direction.Axis normal, double along, Direction.Axis[] plane, double x, double y) {
		double[] xyz = new double[3];
		xyz[normal.ordinal()] = along;
		xyz[plane[0].ordinal()] = x;
		xyz[plane[1].ordinal()] = y;
		return new Vec3(xyz[0], xyz[1], xyz[2]);
	}

	/** The part of a convex polygon (x, y, u, v corners) inside a rectangle (Sutherland–Hodgman). */
	private static List<double[]> clip(List<double[]> polygon, double minX, double maxX, double minY, double maxY) {
		List<double[]> clipped = polygon;
		clipped = clipEdge(clipped, 0, minX, true);
		clipped = clipEdge(clipped, 0, maxX, false);
		clipped = clipEdge(clipped, 1, minY, true);
		return clipEdge(clipped, 1, maxY, false);
	}

	/** Keeps the part of the polygon with its {@code axis} coordinate above {@code limit} ({@code above}) or below it. */
	private static List<double[]> clipEdge(List<double[]> polygon, int axis, double limit, boolean above) {
		List<double[]> out = new ArrayList<>();
		for (int i = 0; i < polygon.size(); i++) {
			double[] a = polygon.get(i);
			double[] b = polygon.get((i + 1) % polygon.size());
			boolean aIn = above ? a[axis] >= limit : a[axis] <= limit;
			boolean bIn = above ? b[axis] >= limit : b[axis] <= limit;
			if (aIn) {
				out.add(a);
			}
			if (aIn != bIn) {
				double t = (limit - a[axis]) / (b[axis] - a[axis]);
				double[] cut = new double[4];
				for (int k = 0; k < 4; k++) {
					cut[k] = a[k] + (b[k] - a[k]) * t;
				}
				out.add(cut);
			}
		}
		return out;
	}
}
