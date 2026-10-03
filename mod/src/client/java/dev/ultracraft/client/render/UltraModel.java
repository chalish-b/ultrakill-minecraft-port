package dev.ultracraft.client.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ultracraft.Ultracraft;
import dev.ultracraft.client.render.animation.Animator;
import dev.ultracraft.client.render.animation.Pose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A model extracted by tools/extract_models.py: a rig of bones with the prefab's local transforms, and
 * textured meshes skinned to them, in ULTRAKILL units on Unity's axes (x right, y up, z forward). Drawing flips
 * z, which gives Minecraft's camera space (-z forward). First-person models are placed relative to the camera,
 * world models relative to themselves. Models with an Animator also carry its controller and clips.
 */
public final class UltraModel {
	private static final Map<String, Optional<UltraModel>> CACHE = new HashMap<>();
	private static final Matrix4f FLIP_Z = new Matrix4f().scaling(1, 1, -1);

	private final Vector3f origin;
	private final int[] parents;
	private final Pose rest;
	private final List<Part> parts;
	private final Animator.@Nullable Data animation;

	private UltraModel(Vector3f origin, int[] parents, Pose rest, List<Part> parts, Animator.@Nullable Data animation) {
		this.origin = origin;
		this.parents = parents;
		this.rest = rest;
		this.parts = parts;
		this.animation = animation;
	}

	/** The model called {@code name}, or null if it hasn't been extracted. */
	public static @Nullable UltraModel get(String name) {
		return CACHE.computeIfAbsent(name, UltraModel::load).orElse(null);
	}

	/** The prefab's own pose. */
	public Pose rest() {
		return this.rest;
	}

	/** A new Animator for this model, if it has one. */
	public @Nullable Animator animator() {
		return this.animation == null ? null : new Animator(this.animation, this.rest);
	}

	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light) {
		submit(poseStack, collector, light, this.rest);
	}

	/** Draws the model in {@code pose}, skinned on the CPU. */
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, Pose pose) {
		Matrix4f[] world = boneMatrices(pose);
		for (Part part : this.parts) {
			float[][] skinned = part.skin(world);
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(part.texture), (p, buffer) -> part.emit(p, buffer, skinned[0], skinned[1], light));
		}
	}

	/** Each bone's matrix in camera space, z already flipped. */
	private Matrix4f[] boneMatrices(Pose pose) {
		Matrix4f[] world = new Matrix4f[this.parents.length];
		Quaternionf rotation = new Quaternionf();
		for (int bone = 0; bone < world.length; bone++) {
			rotation.set(pose.qx(bone), pose.qy(bone), pose.qz(bone), pose.qw(bone));
			Matrix4f local = new Matrix4f().translationRotateScale(
				pose.px(bone), pose.py(bone), pose.pz(bone), rotation.x, rotation.y, rotation.z, rotation.w,
				pose.sx(bone), pose.sy(bone), pose.sz(bone)
			);
			int parent = this.parents[bone];
			Matrix4f base = parent < 0 ? new Matrix4f(FLIP_Z).translate(this.origin) : world[parent];
			world[bone] = new Matrix4f(base).mul(local);
		}
		return world;
	}

	/**
	 * Mesh-space vertices skinned to joints: bones with the bind pose that takes mesh space into theirs. Each
	 * vertex follows up to {@code influences} joints, by weight.
	 */
	private record Part(
		Identifier texture, float[] positions, float[] normals, float[] uvs, int[] indices,
		int[] joints, Matrix4f[] binds, int influences, int[] jointIndices, float[] jointWeights
	) {
		/** Positions and normals in camera space for the given bone matrices. */
		float[][] skin(Matrix4f[] world) {
			Matrix4f[] skins = new Matrix4f[this.joints.length];
			for (int j = 0; j < skins.length; j++) {
				skins[j] = new Matrix4f(world[this.joints[j]]).mul(this.binds[j]);
			}
			float[] positions = new float[this.positions.length];
			float[] normals = new float[this.normals.length];
			Vector3f position = new Vector3f();
			Vector3f normal = new Vector3f();
			for (int v = 0; v < positions.length / 3; v++) {
				float px = 0, py = 0, pz = 0, nx = 0, ny = 0, nz = 0;
				for (int k = 0; k < this.influences; k++) {
					float weight = this.jointWeights[v * this.influences + k];
					if (weight == 0) {
						continue;
					}
					Matrix4f skin = skins[this.jointIndices[v * this.influences + k]];
					skin.transformPosition(this.positions[v * 3], this.positions[v * 3 + 1], this.positions[v * 3 + 2], position);
					skin.transformDirection(this.normals[v * 3], this.normals[v * 3 + 1], this.normals[v * 3 + 2], normal);
					px += position.x * weight;
					py += position.y * weight;
					pz += position.z * weight;
					nx += normal.x * weight;
					ny += normal.y * weight;
					nz += normal.z * weight;
				}
				float length = (float)Math.sqrt(nx * nx + ny * ny + nz * nz);
				if (length > 0) {
					nx /= length;
					ny /= length;
					nz /= length;
				}
				positions[v * 3] = px;
				positions[v * 3 + 1] = py;
				positions[v * 3 + 2] = pz;
				normals[v * 3] = nx;
				normals[v * 3 + 1] = ny;
				normals[v * 3 + 2] = nz;
			}
			return new float[][]{positions, normals};
		}

		/** Triangles as quads with a repeated corner: the entity render types draw quads. */
		void emit(PoseStack.Pose pose, VertexConsumer buffer, float[] positions, float[] normals, int light) {
			for (int i = 0; i < this.indices.length; i += 3) {
				vertex(pose, buffer, positions, normals, this.indices[i], light);
				vertex(pose, buffer, positions, normals, this.indices[i + 1], light);
				vertex(pose, buffer, positions, normals, this.indices[i + 2], light);
				vertex(pose, buffer, positions, normals, this.indices[i + 2], light);
			}
		}

		private void vertex(PoseStack.Pose pose, VertexConsumer buffer, float[] positions, float[] normals, int index, int light) {
			buffer.addVertex(pose, positions[index * 3], positions[index * 3 + 1], positions[index * 3 + 2])
				.setColor(0xFFFFFFFF)
				.setUv(this.uvs[index * 2], this.uvs[index * 2 + 1])
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light)
				.setNormal(pose, normals[index * 3], normals[index * 3 + 1], normals[index * 3 + 2]);
		}
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
			float[] origin = floats(json.getAsJsonArray("origin"));
			JsonArray bones = json.getAsJsonArray("bones");
			int[] parents = new int[bones.size()];
			Pose rest = new Pose(bones.size());
			for (int i = 0; i < parents.length; i++) {
				JsonObject bone = bones.get(i).getAsJsonObject();
				parents[i] = bone.get("parent").getAsInt();
				rest.setBone(i, floats(bone.getAsJsonArray("position")), floats(bone.getAsJsonArray("rotation")), floats(bone.getAsJsonArray("scale")));
			}
			List<Part> parts = new ArrayList<>();
			for (JsonElement element : json.getAsJsonArray("parts")) {
				JsonObject part = element.getAsJsonObject();
				JsonArray bindArray = part.getAsJsonArray("binds");
				Matrix4f[] binds = new Matrix4f[bindArray.size()];
				for (int i = 0; i < binds.length; i++) {
					binds[i] = new Matrix4f().set(floats(bindArray.get(i).getAsJsonArray()));
				}
				parts.add(new Part(
					Identifier.parse(part.get("texture").getAsString()),
					floats(part.getAsJsonArray("positions")),
					floats(part.getAsJsonArray("normals")),
					floats(part.getAsJsonArray("uvs")),
					ints(part.getAsJsonArray("indices")),
					ints(part.getAsJsonArray("joints")),
					binds,
					part.get("influences").getAsInt(),
					ints(part.getAsJsonArray("jointIndices")),
					floats(part.getAsJsonArray("jointWeights"))
				));
			}
			Animator.Data animation = json.has("animator") ? Animator.Data.fromJson(json.getAsJsonObject("animator")) : null;
			return Optional.of(new UltraModel(new Vector3f(origin), parents, rest, parts, animation));
		} catch (IOException | RuntimeException e) {
			Ultracraft.LOGGER.warn("Couldn't load model {}", id, e);
			return Optional.empty();
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
