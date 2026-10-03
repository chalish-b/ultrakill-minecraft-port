package dev.ultracraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a model's built-in quads and canvas images on their bones, as Unity's renderers and UI would: unlit,
 * tinted by a color, and seen from either side.
 */
final class ModelOverlays {
	/** A corner: x and y in the quad's or canvas's space, u and v in the texture (v down, as Minecraft reads it). */
	private record Corner(float x, float y, float u, float v) {
	}

	private ModelOverlays() {
	}

	/** Unity's built-in Quad: 1 x 1, centered, its texture's bottom left at (-0.5, -0.5). */
	static void quad(PoseStack poseStack, SubmitNodeCollector collector, RenderType type, Matrix4f bone, float[] color) {
		List<Corner> corners = List.of(
			new Corner(-0.5F, -0.5F, 0, 1), new Corner(0.5F, -0.5F, 1, 1), new Corner(0.5F, 0.5F, 1, 0), new Corner(-0.5F, 0.5F, 0, 0)
		);
		submit(poseStack, collector, type, bone, color, List.of(corners));
	}

	/** A UI Image filled {@code fillAmount} of the way, if it's a filled image. */
	static void image(PoseStack poseStack, SubmitNodeCollector collector, RenderType type, Matrix4f canvas, UltraModel.Image image, float[] color, float fillAmount) {
		float[] r = image.rect();
		List<List<Corner>> polygons = switch (image.type()) {
			case SLICED -> sliced(r, image.border(), image.uvBorder());
			case FILLED -> image.fillMethod() == UltraModel.FillMethod.RADIAL360
				? radial360(r, image.fillOrigin(), image.fillClockwise(), fillAmount)
				: List.of(rectangle(r));
			case SIMPLE, TILED -> List.of(rectangle(r));
		};
		submit(poseStack, collector, type, canvas, color, polygons);
	}

	private static List<Corner> rectangle(float[] r) {
		return List.of(new Corner(r[0], r[1], 0, 1), new Corner(r[2], r[1], 1, 1), new Corner(r[2], r[3], 1, 0), new Corner(r[0], r[3], 0, 0));
	}

	/** Nine pieces: the corners keep their size, the edges and middle stretch. */
	private static List<List<Corner>> sliced(float[] r, float[] border, float[] uvBorder) {
		float[] xs = {r[0], r[0] + border[0], r[2] - border[2], r[2]};
		float[] ys = {r[1], r[1] + border[1], r[3] - border[3], r[3]};
		float[] us = {0, uvBorder[0], 1 - uvBorder[2], 1};
		float[] vs = {0, uvBorder[1], 1 - uvBorder[3], 1};
		List<List<Corner>> pieces = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				pieces.add(List.of(
					new Corner(xs[i], ys[j], us[i], 1 - vs[j]),
					new Corner(xs[i + 1], ys[j], us[i + 1], 1 - vs[j]),
					new Corner(xs[i + 1], ys[j + 1], us[i + 1], 1 - vs[j + 1]),
					new Corner(xs[i], ys[j + 1], us[i], 1 - vs[j + 1])
				));
			}
		}
		return pieces;
	}

	/**
	 * Image.FillMethod.Radial360: a pie swept from the origin side (bottom, right, top, left), as triangles from
	 * the center to the rectangle's edge.
	 */
	private static List<List<Corner>> radial360(float[] r, int origin, boolean clockwise, float fillAmount) {
		float amount = Math.clamp(fillAmount, 0, 1);
		if (amount <= 0) {
			return List.of();
		}
		double start = Math.toRadians(-90 + 90 * origin);
		double sweep = 2 * Math.PI * amount * (clockwise ? -1 : 1);
		List<Double> angles = new ArrayList<>();
		angles.add(start);
		for (int k = -8; k <= 8; k++) {
			double corner = Math.PI / 4 + k * Math.PI / 2;
			if (clockwise ? corner < start && corner > start + sweep : corner > start && corner < start + sweep) {
				angles.add(corner);
			}
		}
		angles.add(start + sweep);
		angles.sort(clockwise ? (a, b) -> Double.compare(b, a) : Double::compare);
		float cx = (r[0] + r[2]) / 2, cy = (r[1] + r[3]) / 2;
		float hx = (r[2] - r[0]) / 2, hy = (r[3] - r[1]) / 2;
		Corner center = corner(r, cx, cy);
		List<List<Corner>> triangles = new ArrayList<>();
		for (int i = 0; i + 1 < angles.size(); i++) {
			Corner a = edge(r, cx, cy, hx, hy, angles.get(i));
			Corner b = edge(r, cx, cy, hx, hy, angles.get(i + 1));
			triangles.add(List.of(center, a, b, b));
		}
		return triangles;
	}

	private static Corner edge(float[] r, float cx, float cy, float hx, float hy, double angle) {
		double dx = Math.cos(angle), dy = Math.sin(angle);
		double scale = 1 / Math.max(Math.abs(dx), Math.abs(dy));
		return corner(r, (float)(cx + dx * scale * hx), (float)(cy + dy * scale * hy));
	}

	private static Corner corner(float[] r, float x, float y) {
		return new Corner(x, y, (x - r[0]) / (r[2] - r[0]), 1 - (y - r[1]) / (r[3] - r[1]));
	}

	/** Each polygon is four corners (a triangle repeats its last), drawn front and back. */
	private static void submit(PoseStack poseStack, SubmitNodeCollector collector, RenderType type, Matrix4f matrix, float[] color, List<List<Corner>> polygons) {
		if (polygons.isEmpty()) {
			return;
		}
		Matrix4f transform = new Matrix4f(matrix);
		collector.submitCustomGeometry(poseStack, type, (pose, buffer) -> {
			Vector3f position = new Vector3f();
			for (List<Corner> polygon : polygons) {
				for (int i = 0; i < 4; i++) {
					vertex(pose, buffer, transform, polygon.get(i), color, position);
				}
				for (int i = 3; i >= 0; i--) {
					vertex(pose, buffer, transform, polygon.get(i), color, position);
				}
			}
		});
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, Matrix4f matrix, Corner corner, float[] color, Vector3f position) {
		matrix.transformPosition(corner.x(), corner.y(), 0, position);
		buffer.addVertex(pose, position.x, position.y, position.z)
			.setColor(color[0], color[1], color[2], color[3])
			.setUv(corner.u(), corner.v())
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(LightCoordsUtil.FULL_BRIGHT)
			.setNormal(pose, 0, 0, 1);
	}
}
