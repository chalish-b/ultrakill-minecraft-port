package dev.ultracraft.client.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ultracraft.Ultracraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A model extracted by tools/extract_models.py: textured triangles in ULTRAKILL units, x right, y up and -z
 * forward. First-person models are placed relative to the camera; world models relative to themselves.
 */
public final class UltraModel {
	private static final Map<String, Optional<UltraModel>> CACHE = new HashMap<>();

	private final List<Part> parts;
	private final Vec3 center;

	private UltraModel(List<Part> parts) {
		this.parts = parts;
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (Part part : parts) {
			for (int i = 0; i < part.positions.length; i += 3) {
				minX = Math.min(minX, part.positions[i]);
				minY = Math.min(minY, part.positions[i + 1]);
				minZ = Math.min(minZ, part.positions[i + 2]);
				maxX = Math.max(maxX, part.positions[i]);
				maxY = Math.max(maxY, part.positions[i + 1]);
				maxZ = Math.max(maxZ, part.positions[i + 2]);
			}
		}
		this.center = new Vec3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
	}

	/** The model called {@code name}, or null if it hasn't been extracted. */
	public static @Nullable UltraModel get(String name) {
		return CACHE.computeIfAbsent(name, UltraModel::load).orElse(null);
	}

	private static Optional<UltraModel> load(String name) {
		Identifier id = Ultracraft.id("viewmodels/" + name + ".json");
		Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(id);
		if (resource.isEmpty()) {
			Ultracraft.LOGGER.warn("Missing model {}; run tools/extract.sh", id);
			return Optional.empty();
		}
		try (Reader reader = resource.get().openAsReader()) {
			JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
			List<Part> parts = new ArrayList<>();
			for (JsonElement element : json.getAsJsonArray("parts")) {
				JsonObject part = element.getAsJsonObject();
				parts.add(new Part(
					Identifier.parse(part.get("texture").getAsString()),
					floats(part.getAsJsonArray("positions")),
					floats(part.getAsJsonArray("normals")),
					floats(part.getAsJsonArray("uvs")),
					ints(part.getAsJsonArray("indices"))
				));
			}
			return Optional.of(new UltraModel(parts));
		} catch (IOException | RuntimeException e) {
			Ultracraft.LOGGER.warn("Couldn't load model {}", id, e);
			return Optional.empty();
		}
	}

	/** The middle of the model's bounds. */
	public Vec3 center() {
		return this.center;
	}

	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light) {
		for (Part part : this.parts) {
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(part.texture), (pose, buffer) -> part.emit(pose, buffer, light));
		}
	}

	private record Part(Identifier texture, float[] positions, float[] normals, float[] uvs, int[] indices) {
		/** Triangles as quads with a repeated corner: the entity render types draw quads. */
		void emit(PoseStack.Pose pose, VertexConsumer buffer, int light) {
			for (int i = 0; i < this.indices.length; i += 3) {
				vertex(pose, buffer, this.indices[i], light);
				vertex(pose, buffer, this.indices[i + 1], light);
				vertex(pose, buffer, this.indices[i + 2], light);
				vertex(pose, buffer, this.indices[i + 2], light);
			}
		}

		private void vertex(PoseStack.Pose pose, VertexConsumer buffer, int index, int light) {
			buffer.addVertex(pose, this.positions[index * 3], this.positions[index * 3 + 1], this.positions[index * 3 + 2])
				.setColor(0xFFFFFFFF)
				.setUv(this.uvs[index * 2], this.uvs[index * 2 + 1])
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light)
				.setNormal(pose, this.normals[index * 3], this.normals[index * 3 + 1], this.normals[index * 3 + 2]);
		}
	}

	private static float[] floats(JsonArray array) {
		float[] values = new float[array.size()];
		for (int i = 0; i < values.length; i++) {
			values[i] = array.get(i).getAsFloat();
		}
		return values;
	}

	private static int[] ints(JsonArray array) {
		int[] values = new int[array.size()];
		for (int i = 0; i < values.length; i++) {
			values[i] = array.get(i).getAsInt();
		}
		return values;
	}
}
