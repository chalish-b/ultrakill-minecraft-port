# /// script
# dependencies = ["UnityPy", "numpy", "pillow"]
# ///
"""Extracts ULTRAKILL's first-person models into the mod's generated resources (mod/src/generated/resources).

    uv run tools/extract_models.py [--preview DIR]

Each model is posed with the first frame of one of its animation clips (or as its prefab stores it),
skinned on the CPU, and written in camera space: ULTRAKILL units, x right, y up, -z forward, as Minecraft draws first-person hands. Writes
assets/ultracraft/viewmodels/<name>.json and the textures they use under textures/viewmodel/, plus each
weapon's HUD icon, tinted with its variant's color, as textures/item/<name>.png.
"""
import argparse
import json
import re
import shutil
import struct
import sys
import zlib
from pathlib import Path

import numpy as np
import UnityPy
from UnityPy.helpers.MeshHelper import MeshHandler

sys.path.insert(0, str(Path(__file__).parent))
from ultrakill_paths import BUNDLES, REPO  # noqa: E402

OUT = REPO / "mod" / "src" / "generated" / "resources" / "assets" / "ultracraft"
NAMESPACE = "ultracraft"

# Model name -> (prefab root name, script on the root, the root's parent offset from the camera, pose clip).
# Guns hang directly off the camera; the arm hangs off FistControl, 0.225 units below it. Models drawn in the
# world have no offset and are written relative to their own root.
MODELS = {
    "revolver_piercer": ("Revolver Pierce", "Revolver", (0, 0, 0), "Idle"),
    "revolver_marksman": ("Revolver Ricochet", "Revolver", (0, 0, 0), "Idle"),
    "feedbacker": ("Arm Blue", "Punch", (0, -0.225, 0), None),
    "coin": ("Coin", "Coin", None, None),
}


def bundles():
    assets = BUNDLES / "assets_assets_assets"
    skip = {"sounds.bundle", "videos.bundle", "shaders.bundle"}
    return [str(BUNDLES / "gameprefabs_assets_all.bundle"), str(BUNDLES / "other_assets_all.bundle"),
            str(BUNDLES / "monoscript_monoscripts.bundle")] + [str(p) for p in assets.glob("*.bundle") if p.name not in skip]


def script_name(obj):
    try:
        return obj.read().m_Script.read().m_ClassName
    except Exception:
        return None


def trs(transform, pose=None):
    """A Transform's local matrix (Unity's T * R * S), with any values a pose overrides."""
    t, q, s = transform.m_LocalPosition, transform.m_LocalRotation, transform.m_LocalScale
    t, q, s = (t.x, t.y, t.z), (q.x, q.y, q.z, q.w), (s.x, s.y, s.z)
    overrides = (pose or {}).get(transform.object_reader.path_id, {})
    t, q, s = overrides.get("position", t), overrides.get("rotation", q), overrides.get("scale", s)
    x, y, z, w = q
    rotation = np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ])
    m = np.eye(4)
    m[:3, :3] = rotation * np.array(s)
    m[:3, 3] = t
    return m


def matrix(m):
    """A serialized Matrix4x4f (column-major eXY fields) as a numpy matrix."""
    return np.array([[getattr(m, f"e{r}{c}") for c in range(4)] for r in range(4)])


def sample_clip(clip):
    """Every curve's value on the clip's first frame, in curve order: streamed, dense, then constant."""
    data = clip.m_MuscleClip.m_Clip
    data = getattr(data, "data", data)
    streamed = data.m_StreamedClip
    values = [0.0] * streamed.curveCount
    raw = struct.pack(f"<{len(streamed.data)}I", *streamed.data)
    pos = 0
    while pos < len(raw):
        time, count = struct.unpack_from("<fi", raw, pos)
        pos += 8
        if time > 0:
            break
        for i in range(count):
            index, *coefficients = struct.unpack_from("<i4f", raw, pos + i * 20)
            values[index] = coefficients[3]
        pos += count * 20
    dense = data.m_DenseClip
    values += list(dense.m_SampleArray[:dense.m_CurveCount])
    values += list(data.m_ConstantClip.data)
    return values


def clip_pose(animator, clip_name):
    """Transform overrides (by Transform path id) for the first frame of an Animator's clip."""
    controller = animator.m_Controller.deref().read()
    clip = next((c.deref().read() for c in controller.m_AnimationClips if c.deref().read().m_Name == clip_name), None)
    if clip is None:
        sys.exit(f"no clip {clip_name} on {animator.m_GameObject.read().m_Name}")
    paths = {}

    def walk(transform, path):
        paths[zlib.crc32(path.encode())] = transform.object_reader.path_id
        for child in transform.m_Children:
            t = child.deref().read()
            name = t.m_GameObject.read().m_Name
            walk(t, f"{path}/{name}" if path else name)

    root = next(c.component.deref().read() for c in animator.m_GameObject.read().m_Component if c.component.deref().type.name == "Transform")
    walk(root, "")
    values = sample_clip(clip)
    pose = {}
    index = 0
    for binding in clip.m_ClipBindingConstant.genericBindings:
        size = {1: 3, 2: 4, 3: 3, 4: 3}.get(binding.attribute, 1) if binding.typeID == 4 else 1
        target = paths.get(binding.path)
        if binding.typeID == 4 and target is not None and binding.attribute in (1, 2, 3):
            key = {1: "position", 2: "rotation", 3: "scale"}[binding.attribute]
            pose.setdefault(target, {})[key] = tuple(values[index:index + size])
        index += size
    return pose


class Prefab:
    """World matrices of every transform under a prefab root placed at `placement`."""

    def __init__(self, root_go, placement, pose=None):
        """`placement` is the root's parent's matrix, or None to put the root itself at the origin."""
        self.world = {}
        self.active = {}
        self.pose = pose
        root = next(c.component.deref().read() for c in root_go.m_Component if c.component.deref().type.name == "Transform")
        self._walk(root, np.eye(4) if placement is None else placement @ trs(root, pose), True, True)

    def _walk(self, transform, world, active, is_root=False):
        go = transform.m_GameObject.read()
        # Weapon prefabs are stored with their root inactive; the game activates it on equip.
        active = active and (is_root or go.m_IsActive)
        self.world[transform.object_reader.path_id] = world
        self.active[go.object_reader.path_id] = active
        for child in transform.m_Children:
            t = child.deref().read()
            self._walk(t, world @ trs(t, self.pose), active)

    def gameobjects(self):
        return self.active.keys()


def texture_name(material):
    for key, env in material.m_SavedProperties.m_TexEnvs:
        if key == "_MainTex" and env.m_Texture.path_id:
            return env.m_Texture, (env.m_Scale.x, env.m_Scale.y), (env.m_Offset.x, env.m_Offset.y)
    return None, (1, 1), (0, 0)


def file_name(name):
    return re.sub(r"[^a-z0-9_]", "_", name.lower()).strip("_")


class Exporter:
    def __init__(self):
        self.textures = {}

    def texture(self, pptr):
        tex = pptr.deref().read()
        name = file_name(tex.m_Name)
        if name not in self.textures:
            path = OUT / "textures" / "viewmodel" / f"{name}.png"
            path.parent.mkdir(parents=True, exist_ok=True)
            tex.image.save(path)
            self.textures[name] = path
        return f"{NAMESPACE}:textures/viewmodel/{name}.png", name

    def renderer_parts(self, renderer, mesh, positions, normals):
        handler = MeshHandler(mesh)
        handler.process()
        uv = np.array(handler.m_UV0, dtype=float)
        parts = []
        for i, triangles in enumerate(handler.get_triangles()):
            if i >= len(renderer.m_Materials) or not renderer.m_Materials[i].path_id:
                continue
            material = renderer.m_Materials[i].deref().read()
            tex_pptr, scale, offset = texture_name(material)
            if tex_pptr is None:
                continue
            texture, tex_file = self.texture(tex_pptr)
            used = sorted({v for tri in triangles for v in tri})
            remap = {v: n for n, v in enumerate(used)}
            u = uv[used, 0] * scale[0] + offset[0]
            v = 1 - (uv[used, 1] * scale[1] + offset[1])
            parts.append({
                "texture": texture,
                "textureFile": tex_file,
                "material": material.m_Name,
                "positions": np.round(positions[used], 5).flatten().tolist(),
                "normals": np.round(normals[used], 4).flatten().tolist(),
                "uvs": np.round(np.stack([u, v], axis=1), 5).flatten().tolist(),
                # Reversed winding: flipping z changes handedness.
                "indices": [remap[x] for tri in triangles for x in (tri[0], tri[2], tri[1])],
            })
        return parts


def to_minecraft(points):
    return points * np.array([1, 1, -1])


def find_animator(root_go, prefab):
    """The first Animator in the prefab, depth first."""
    for go_id in prefab.active:
        go = _objects[go_id].read()
        for c in go.m_Component:
            if c.component.deref().type.name == "Animator":
                return c.component.deref().read()
    return None


def export_model(env, exporter, root_go, offset, clip_name):
    placement = None
    if offset is not None:
        placement = np.eye(4)
        placement[:3, 3] = offset
    prefab = Prefab(root_go, placement)
    if clip_name:
        prefab = Prefab(root_go, placement, clip_pose(find_animator(root_go, prefab), clip_name))
    parts = []
    for go_id, active in prefab.active.items():
        if not active:
            continue
        go = env_object(env, go_id).read()
        for c in go.m_Component:
            obj = c.component.deref()
            kind = obj.type.name
            if kind == "SkinnedMeshRenderer":
                r = obj.read()
                if not r.m_Enabled or not r.m_Mesh.path_id:
                    continue
                mesh = r.m_Mesh.deref().read()
                handler = MeshHandler(mesh)
                handler.process()
                verts = np.c_[np.array(handler.m_Vertices, dtype=float).reshape(-1, 3), np.ones(handler.m_VertexCount)]
                norms = np.array(handler.m_Normals, dtype=float).reshape(-1, 3)
                bones = [prefab.world.get(b.path_id) for b in r.m_Bones]
                bind = [matrix(m) for m in mesh.m_BindPose]
                indices = np.array(handler.m_BoneIndices, dtype=int).reshape(handler.m_VertexCount, -1)[:, 0]
                skin = [b @ p if b is not None else np.eye(4) for b, p in zip(bones, bind)]
                positions = np.array([(skin[i] @ v)[:3] for i, v in zip(indices, verts)])
                normals = np.array([skin[i][:3, :3] @ n for i, n in zip(indices, norms)])
            elif kind == "MeshRenderer":
                r = obj.read()
                if not r.m_Enabled:
                    continue
                mesh_filter = next((x.component.deref().read() for x in go.m_Component if x.component.deref().type.name == "MeshFilter"), None)
                if mesh_filter is None or not mesh_filter.m_Mesh.path_id:
                    continue
                try:
                    mesh = mesh_filter.m_Mesh.deref().read()
                except FileNotFoundError:
                    continue  # Unity's built-in primitives, used for effects
                handler = MeshHandler(mesh)
                handler.process()
                world = prefab.world[transform_id(go)]
                verts = np.c_[np.array(handler.m_Vertices, dtype=float).reshape(-1, 3), np.ones(handler.m_VertexCount)]
                positions = (world @ verts.T).T[:, :3]
                normals = (world[:3, :3] @ np.array(handler.m_Normals, dtype=float).reshape(-1, 3).T).T
            else:
                continue
            lengths = np.linalg.norm(normals, axis=1, keepdims=True)
            normals = normals / np.where(lengths == 0, 1, lengths)
            for part in exporter.renderer_parts(r, mesh, to_minecraft(positions), to_minecraft(normals)):
                part["name"] = go.m_Name
                parts.append(part)
    return parts


_objects = {}


def env_object(env, path_id):
    return _objects[path_id]


def transform_id(go):
    return next(c.component.path_id for c in go.m_Component if c.component.deref().type.name == "Transform")


def find_roots(env):
    """The prefab root GameObject for each model."""
    found = {}
    for obj in env.objects:
        if obj.type.name == "GameObject":
            _objects[obj.path_id] = obj
    for obj in env.objects:
        if obj.type.name != "MonoBehaviour":
            continue
        cls = script_name(obj)
        for model, (root_name, script, offset, clip) in MODELS.items():
            if model in found or cls != script:
                continue
            go = obj.read().m_GameObject.read()
            if go.m_Name != root_name:
                continue
            tree = obj.read_typetree()
            if tree.get("altVersion"):
                continue
            found[model] = (go, offset, clip)
    return found


def preview(model, parts, directory):
    """Renders the model as the camera sees it (90 degree vertical FOV), for checking the pose."""
    from PIL import Image, ImageDraw
    width, height = 960, 540
    image = Image.new("RGB", (width, height), (40, 44, 52))
    draw = ImageDraw.Draw(image)
    focal = (height / 2) / np.tan(np.radians(45))
    tris = []
    textures = {}
    for part in parts:
        if part["textureFile"] not in textures:
            textures[part["textureFile"]] = Image.open(OUT / "textures" / "viewmodel" / f"{part['textureFile']}.png").convert("RGB")
        tex = textures[part["textureFile"]]
        pos = np.array(part["positions"]).reshape(-1, 3)
        uv = np.array(part["uvs"]).reshape(-1, 2)
        nrm = np.array(part["normals"]).reshape(-1, 3)
        idx = np.array(part["indices"]).reshape(-1, 3)
        for a, b, c in idx:
            p = pos[[a, b, c]]
            if np.any(p[:, 2] > -0.05):
                continue
            centroid_uv = uv[[a, b, c]].mean(axis=0) % 1
            color = np.array(tex.getpixel((int(centroid_uv[0] * (tex.width - 1)), int(centroid_uv[1] * (tex.height - 1)))), dtype=float)
            shade = 0.45 + 0.55 * max(0.0, float(np.dot(nrm[[a, b, c]].mean(axis=0), [0.3, 0.8, 0.5])))
            screen = [(width / 2 + focal * x / -z, height / 2 - focal * y / -z) for x, y, z in p]
            tris.append((p[:, 2].mean(), screen, tuple((color * shade).clip(0, 255).astype(int))))
    for _, screen, color in sorted(tris, key=lambda t: t[0]):
        draw.polygon(screen, fill=color)
    directory.mkdir(parents=True, exist_ok=True)
    image.save(directory / f"{model}.png")


def variation_colors(env):
    for obj in env.objects:
        if obj.type.name == "MonoBehaviour" and script_name(obj) == "ColorBlindSettings":
            return [(c["r"], c["g"], c["b"]) for c in obj.read_typetree()["variationColors"]]
    sys.exit("no ColorBlindSettings")


def export_icon(root_go, model, colors):
    """The weapon's HUD icon, tinted with its variant color."""
    from PIL import Image
    icon = next((c.component.deref().read() for c in root_go.m_Component
                 if c.component.deref().type.name == "MonoBehaviour" and script_name(c.component.deref()) == "WeaponIcon"), None)
    if icon is None:
        return
    descriptor = icon.weaponDescriptor.deref().read()
    image = descriptor.icon.deref().read().image.convert("RGBA")
    r, g, b = colors[descriptor.variationColor]
    tinted = Image.new("RGBA", image.size, (int(r * 255), int(g * 255), int(b * 255), 255))
    tinted.putalpha(image.getchannel("A"))
    side = max(tinted.size)
    square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    square.paste(tinted, ((side - tinted.width) // 2, (side - tinted.height) // 2))
    tinted = square.resize((64, 64), Image.LANCZOS)
    path = OUT / "textures" / "item" / f"{model}.png"
    path.parent.mkdir(parents=True, exist_ok=True)
    tinted.save(path)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", type=Path, help="also render a preview image per model into this folder")
    args = parser.parse_args()

    env = UnityPy.load(*bundles())
    roots = find_roots(env)
    missing = set(MODELS) - set(roots)
    if missing:
        sys.exit(f"models not found: {sorted(missing)}")

    for folder in ("viewmodels", "textures/viewmodel", "textures/item"):
        if (OUT / folder).exists():
            shutil.rmtree(OUT / folder)
    (OUT / "viewmodels").mkdir(parents=True)

    exporter = Exporter()
    colors = variation_colors(env)
    for model, (root, offset, clip) in roots.items():
        export_icon(root, model, colors)
        parts = export_model(env, exporter, root, offset, clip)
        (OUT / "viewmodels" / f"{model}.json").write_text(json.dumps({"parts": parts}))
        print(f"{model}: {len(parts)} parts, {sum(len(p['indices']) // 3 for p in parts)} triangles, "
              f"{sorted({p['name'] for p in parts})}")
        if args.preview:
            preview(model, parts, args.preview)
    print(f"{len(exporter.textures)} textures -> {OUT.relative_to(REPO)}")


if __name__ == "__main__":
    main()
