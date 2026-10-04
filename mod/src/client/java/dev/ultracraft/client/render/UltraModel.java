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
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.LightCoordsUtil;
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
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A model extracted by tools/extract_models.py: a rig of bones with the prefab's local transforms, and
 * textured meshes skinned to them, in ULTRAKILL units on Unity's axes (x right, y up, z forward). Drawing flips
 * z, which gives Minecraft's camera space (-z forward). First-person models are placed relative to the camera,
 * world models relative to themselves. Models with an Animator also carry its controller and clips. Built-in
 * quads (screens, glows), world-space UI canvases and values of the ULTRAKILL scripts on the model come along
 * for the code that ports those scripts to draw.
 */
public final class UltraModel {
	private static final Map<String, Optional<UltraModel>> CACHE = new HashMap<>();
	private static final Matrix4f FLIP_Z = new Matrix4f().scaling(1, 1, -1);

	/** A Unity built-in Quad (1 x 1, centered) on a bone. */
	public record Quad(String name, int bone, @Nullable Identifier texture) {
	}

	/**
	 * A UI Image on a world-space canvas, laid out in the canvas's units.
	 *
	 * @param rect left, bottom, right, top
	 * @param border sliced borders (left, bottom, right, top) in canvas units; {@code uvBorder} in texture space
	 * @param fillOrigin where a radial fill starts: bottom, right, top or left
	 */
	public record Image(
		String name, float[] rect, Identifier texture, float[] color, ImageType type, float[] border, float[] uvBorder,
		FillMethod fillMethod, int fillOrigin, boolean fillClockwise, float fillAmount
	) {
		/** The same image laid out over another rect, as a Slider moves its fill. */
		public Image withRect(float[] rect) {
			return new Image(this.name, rect, this.texture, this.color, this.type, this.border, this.uvBorder, this.fillMethod, this.fillOrigin, this.fillClockwise, this.fillAmount);
		}
	}

	public enum FillMethod {
		HORIZONTAL,
		VERTICAL,
		RADIAL90,
		RADIAL180,
		RADIAL360
	}

	public enum ImageType {
		SIMPLE,
		SLICED,
		TILED,
		FILLED
	}

	public record Canvas(int bone, List<Image> images) {
	}

	/**
	 * A SpriteRenderer on a bone.
	 *
	 * @param rect left, bottom, right, top in the bone's space
	 */
	public record Sprite(String name, int bone, Identifier texture, float[] rect, float[] color) {
	}

	private final Vector3f origin;
	private final String[] names;
	private final int[] parents;
	private final Pose rest;
	private final List<Part> parts;
	private final List<Quad> quads;
	private final List<Sprite> sprites;
	private final List<Canvas> canvases;
	private final JsonObject scripts;
	private final JsonArray particles;
	private final Animator.@Nullable Data animation;

	private UltraModel(
		Vector3f origin, String[] names, int[] parents, Pose rest, List<Part> parts, List<Quad> quads, List<Sprite> sprites, List<Canvas> canvases,
		JsonObject scripts, JsonArray particles, Animator.@Nullable Data animation
	) {
		this.origin = origin;
		this.names = names;
		this.parents = parents;
		this.rest = rest;
		this.parts = parts;
		this.quads = quads;
		this.sprites = sprites;
		this.canvases = canvases;
		this.scripts = scripts;
		this.particles = particles;
		this.animation = animation;
	}

	/** The model called {@code name}, or null if it hasn't been extracted. */
	public static @Nullable UltraModel get(String name) {
		return CACHE.computeIfAbsent(name, UltraModel::load).orElse(null);
	}

	/** Where the model's root hangs from, in camera space for first-person models (Unity units and axes). */
	public Vector3f origin() {
		return new Vector3f(this.origin);
	}

	/** The prefab's own pose. */
	public Pose rest() {
		return this.rest;
	}

	/** A new Animator for this model, if it has one. */
	public @Nullable Animator animator() {
		return this.animation == null ? null : new Animator(this.animation, this.rest);
	}

	public List<Quad> quads() {
		return this.quads;
	}

	public List<Sprite> sprites() {
		return this.sprites;
	}

	public List<Canvas> canvases() {
		return this.canvases;
	}

	/** The values extracted from an ULTRAKILL script on the model ("revolver", "cylinder"), if it has one. */
	public @Nullable JsonObject script(String name) {
		return this.scripts.has(name) ? this.scripts.getAsJsonObject(name) : null;
	}

	/** The values of every script of a kind on the model (Explosion, ScaleNFade and the like), each with its bone; empty if none. */
	public JsonArray scripts(String kind) {
		return this.scripts.has(kind) ? this.scripts.getAsJsonArray(kind) : new JsonArray();
	}

	/** The model's particle systems' settings, each with its bone and its materials. */
	public JsonArray particles() {
		return this.particles;
	}

	/** The bone's parent, or -1 for the root. */
	public int parent(int bone) {
		return this.parents[bone];
	}

	public int bones() {
		return this.parents.length;
	}

	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light) {
		submit(poseStack, collector, light, boneMatrices(this.rest, new Quaternionf()));
	}

	/** Draws the meshes with the given bone matrices, skinned on the CPU, and their glow on top. */
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, Matrix4f[] world) {
		submit(poseStack, collector, light, world, Set.of());
	}

	/** Like {@link #submit(PoseStack, SubmitNodeCollector, int, Matrix4f[])}, leaving out the meshes with these materials. */
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, Matrix4f[] world, Set<String> exceptMaterials) {
		submit(poseStack, collector, light, world, part -> !exceptMaterials.contains(part.material));
	}

	/** Like {@link #submit(PoseStack, SubmitNodeCollector, int, Matrix4f[])}, only the meshes on the objects {@code object} accepts, by name. */
	public void submitObjects(PoseStack poseStack, SubmitNodeCollector collector, int light, Matrix4f[] world, Predicate<String> object) {
		submit(poseStack, collector, light, world, part -> object.test(part.object));
	}

	private void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, Matrix4f[] world, Predicate<Part> drawn) {
		for (Part part : this.parts) {
			if (!drawn.test(part)) {
				continue;
			}
			float[][] skinned = part.skin(world);
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(part.texture), (p, buffer) -> part.emit(p, buffer, skinned[0], skinned[1], 0xFFFFFFFF, light));
			if (part.emissive != null) {
				collector.submitCustomGeometry(poseStack, part.emissive, (p, buffer) -> part.emit(p, buffer, skinned[0], skinned[1], part.emissiveColor, LightCoordsUtil.FULL_BRIGHT));
			}
		}
	}

	/**
	 * Each bone's matrix in camera space, z already flipped.
	 *
	 * @param originRotation turns the model about its origin (Unity space), as a parent transform would
	 */
	public Matrix4f[] boneMatrices(Pose pose, Quaternionf originRotation) {
		Matrix4f[] world = new Matrix4f[this.parents.length];
		Quaternionf rotation = new Quaternionf();
		for (int bone = 0; bone < world.length; bone++) {
			rotation.set(pose.qx(bone), pose.qy(bone), pose.qz(bone), pose.qw(bone));
			Matrix4f local = new Matrix4f().translationRotateScale(
				pose.px(bone), pose.py(bone), pose.pz(bone), rotation.x, rotation.y, rotation.z, rotation.w,
				pose.sx(bone), pose.sy(bone), pose.sz(bone)
			);
			int parent = this.parents[bone];
			Matrix4f base = parent < 0 ? new Matrix4f(FLIP_Z).translate(this.origin).rotate(originRotation) : world[parent];
			world[bone] = new Matrix4f(base).mul(local);
		}
		return world;
	}

	/** The meshes with this material, drawn with their texture in the given render type and color (ARGB). */
	public void submitMaterial(PoseStack poseStack, SubmitNodeCollector collector, Matrix4f[] world, String material, Function<Identifier, RenderType> type, int color, int light) {
		submitMaterial(poseStack, collector, world, material, type, color, light, 0);
	}

	/** Like {@link #submitMaterial(PoseStack, SubmitNodeCollector, Matrix4f[], String, Function, int, int)}, with the texture scrolled across by {@code scroll}. */
	public void submitMaterial(PoseStack poseStack, SubmitNodeCollector collector, Matrix4f[] world, String material, Function<Identifier, RenderType> type, int color, int light, float scroll) {
		for (Part part : this.parts) {
			if (part.material.equals(material)) {
				float[][] skinned = part.skin(world);
				collector.submitCustomGeometry(poseStack, type.apply(part.texture), (p, buffer) -> part.emit(p, buffer, skinned[0], skinned[1], color, light, scroll));
			}
		}
	}

	/** A mesh as stored: in its bone's space, Unity's axes. */
	public record Mesh(Identifier texture, float[] positions, float[] normals, float[] uvs, int[] indices) {
	}

	public int meshCount() {
		return this.parts.size();
	}

	/** The bone the {@code index}th mesh is on (a mesh that isn't skinned follows one bone). */
	public int meshBone(int index) {
		return this.parts.get(index).joints[0];
	}

	/** The name of the {@code index}th mesh's material. */
	public String meshMaterialName(int index) {
		return this.parts.get(index).material;
	}

	/** The {@code index}th mesh's material: its colors, keywords, blending and texture's mean alpha. */
	public JsonObject meshMaterial(int index) {
		return this.parts.get(index).materialValues;
	}

	/** The {@code index}th mesh, for drawing it some other way than {@link #submit}. */
	public Mesh mesh(int index) {
		Part part = this.parts.get(index);
		return new Mesh(part.texture, part.positions, part.normals, part.uvs, part.indices);
	}

	/** The first bone with this name, or -1. */
	public int bone(String name) {
		for (int i = 0; i < this.names.length; i++) {
			if (this.names[i].equals(name)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Mesh-space vertices skinned to joints: bones with the bind pose that takes mesh space into theirs. Each
	 * vertex follows up to {@code influences} joints, by weight. {@code emissive} draws the glow texture over it,
	 * tinted by {@code emissiveColor} (ARGB).
	 */
	private record Part(
		String object, String material, JsonObject materialValues, Identifier texture, float[] positions, float[] normals, float[] uvs, int[] indices,
		int[] joints, Matrix4f[] binds, int influences, int[] jointIndices, float[] jointWeights, @Nullable RenderType emissive, int emissiveColor
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
		void emit(PoseStack.Pose pose, VertexConsumer buffer, float[] positions, float[] normals, int color, int light) {
			emit(pose, buffer, positions, normals, color, light, 0);
		}

		/** {@code scroll} moves the texture across, as a material's scroll offset does. */
		void emit(PoseStack.Pose pose, VertexConsumer buffer, float[] positions, float[] normals, int color, int light, float scroll) {
			for (int i = 0; i < this.indices.length; i += 3) {
				vertex(pose, buffer, positions, normals, this.indices[i], color, light, scroll);
				vertex(pose, buffer, positions, normals, this.indices[i + 1], color, light, scroll);
				vertex(pose, buffer, positions, normals, this.indices[i + 2], color, light, scroll);
				vertex(pose, buffer, positions, normals, this.indices[i + 2], color, light, scroll);
			}
		}

		private void vertex(PoseStack.Pose pose, VertexConsumer buffer, float[] positions, float[] normals, int index, int color, int light, float scroll) {
			buffer.addVertex(pose, positions[index * 3], positions[index * 3 + 1], positions[index * 3 + 2])
				.setColor(color)
				.setUv(this.uvs[index * 2] + scroll, this.uvs[index * 2 + 1])
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
			String[] names = new String[bones.size()];
			Pose rest = new Pose(bones.size());
			for (int i = 0; i < parents.length; i++) {
				JsonObject bone = bones.get(i).getAsJsonObject();
				parents[i] = bone.get("parent").getAsInt();
				names[i] = bone.get("name").getAsString();
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
					part.get("name").getAsString(),
					part.get("material").getAsString(),
					part.has("materialValues") ? part.getAsJsonObject("materialValues") : new JsonObject(),
					Identifier.parse(part.get("texture").getAsString()),
					floats(part.getAsJsonArray("positions")),
					floats(part.getAsJsonArray("normals")),
					floats(part.getAsJsonArray("uvs")),
					ints(part.getAsJsonArray("indices")),
					ints(part.getAsJsonArray("joints")),
					binds,
					part.get("influences").getAsInt(),
					ints(part.getAsJsonArray("jointIndices")),
					floats(part.getAsJsonArray("jointWeights")),
					part.has("emissive") ? RenderTypes.energySwirl(Identifier.parse(part.get("emissive").getAsString()), 0, 0) : null,
					part.has("emissiveColor") ? argb(floats(part.getAsJsonArray("emissiveColor"))) : 0xFFFFFFFF
				));
			}
			List<Quad> quads = new ArrayList<>();
			for (JsonElement element : json.getAsJsonArray("quads")) {
				JsonObject quad = element.getAsJsonObject();
				JsonElement texture = quad.get("texture");
				quads.add(new Quad(quad.get("name").getAsString(), quad.get("bone").getAsInt(), texture.isJsonNull() ? null : Identifier.parse(texture.getAsString())));
			}
			List<Sprite> sprites = new ArrayList<>();
			for (JsonElement element : json.getAsJsonArray("sprites")) {
				JsonObject sprite = element.getAsJsonObject();
				sprites.add(new Sprite(
					sprite.get("name").getAsString(),
					sprite.get("bone").getAsInt(),
					Identifier.parse(sprite.get("texture").getAsString()),
					floats(sprite.getAsJsonArray("rect")),
					floats(sprite.getAsJsonArray("color"))
				));
			}
			List<Canvas> canvases = new ArrayList<>();
			for (JsonElement element : json.getAsJsonArray("canvases")) {
				JsonObject canvas = element.getAsJsonObject();
				List<Image> images = new ArrayList<>();
				for (JsonElement i : canvas.getAsJsonArray("images")) {
					JsonObject image = i.getAsJsonObject();
					images.add(new Image(
						image.get("name").getAsString(),
						floats(image.getAsJsonArray("rect")),
						Identifier.parse(image.get("texture").getAsString()),
						floats(image.getAsJsonArray("color")),
						ImageType.valueOf(image.get("type").getAsString().toUpperCase()),
						floats(image.getAsJsonArray("border")),
						floats(image.getAsJsonArray("uvBorder")),
						FillMethod.valueOf(image.get("fillMethod").getAsString().toUpperCase()),
						image.get("fillOrigin").getAsInt(),
						image.get("fillClockwise").getAsBoolean(),
						image.get("fillAmount").getAsFloat()
					));
				}
				canvases.add(new Canvas(canvas.get("bone").getAsInt(), images));
			}
			Animator.Data animation = json.has("animator") ? Animator.Data.fromJson(json.getAsJsonObject("animator")) : null;
			return Optional.of(new UltraModel(new Vector3f(origin), names, parents, rest, parts, quads, sprites, canvases, json.getAsJsonObject("scripts"),
				json.has("particles") ? json.getAsJsonArray("particles") : new JsonArray(), animation));
		} catch (IOException | RuntimeException e) {
			Ultracraft.LOGGER.warn("Couldn't load model {}", id, e);
			return Optional.empty();
		}
	}

	private static int argb(float[] rgb) {
		return 0xFF000000 | Math.round(rgb[0] * 255) << 16 | Math.round(rgb[1] * 255) << 8 | Math.round(rgb[2] * 255);
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
