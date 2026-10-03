# /// script
# dependencies = ["UnityPy", "numpy", "pillow"]
# ///
"""Extracts ULTRAKILL's first-person models into the mod's generated resources (mod/src/generated/resources).

    uv run tools/extract_models.py [--preview DIR]

Each model is written as a rig: its transform hierarchy with the prefab's local values, and its meshes with
the bones (and bind poses) each vertex follows. Models with an Animator also carry its controller (states,
transitions, parameters) and clips (curves and events), for the mod to play. Everything is in Unity's space:
ULTRAKILL units, x right, y up, z forward; the mod flips z when drawing. Writes
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

# Model name -> (prefab root name, script on the root, the root's parent offset from the camera).
# Guns hang directly off the camera; the arm hangs off FistControl, 0.225 units below it. Models drawn in the
# world have no offset and are written relative to their own root.
MODELS = {
    "revolver_piercer": ("Revolver Pierce", "Revolver", (0, 0, 0)),
    "revolver_marksman": ("Revolver Ricochet", "Revolver", (0, 0, 0)),
    "revolver_sharpshooter": ("Revolver Twirl", "Revolver", (0, 0, 0)),
    "feedbacker": ("Arm Blue", "Punch", (0, -0.225, 0)),
    "knuckleblaster": ("Arm Red", "Punch", (0, -0.225, 0)),
    "coin": ("Coin", "Coin", None),
}

# AnimatorControllerParameterType and AnimatorConditionMode values.
PARAMETER_TYPES = {1: "float", 3: "int", 4: "bool", 9: "trigger"}
CONDITION_MODES = {1: "if", 2: "ifNot", 3: "greater", 4: "less", 6: "equals", 7: "notEqual"}
# Transform curve attributes (binding.attribute when binding.typeID is Transform's class ID, 4).
TRANSFORM_PROPERTIES = {1: ("position", 3), 2: ("rotation", 4), 3: ("scale", 3), 4: ("euler", 3)}
TRANSFORM_CLASS = 4


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


def local_matrix(position, rotation, scale):
    """Unity's T * R * S."""
    x, y, z, w = rotation
    r = np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ])
    m = np.eye(4)
    m[:3, :3] = r * np.array(scale)
    m[:3, 3] = position
    return m


def matrix(m):
    """A serialized Matrix4x4f (eRC fields) as a numpy matrix."""
    return np.array([[getattr(m, f"e{r}{c}") for c in range(4)] for r in range(4)])


def transform_of(go):
    return next(c.component.deref().read() for c in go.m_Component if c.component.deref().type.name in ("Transform", "RectTransform"))


class Rig:
    """Every transform under a prefab root, depth first, with its parent and the prefab's local values."""

    def __init__(self, root_go, at_origin):
        """`at_origin` puts the root itself at the origin instead of where the prefab stores it."""
        self.bones = []
        self.index = {}
        self.active = {}
        self.transforms = {}
        self._walk(transform_of(root_go), -1, True, at_origin)

    def _walk(self, transform, parent, active, at_origin=False, parent_is_rect=False):
        go = transform.m_GameObject.read()
        # Weapon prefabs are stored with their root inactive; the game activates it on equip.
        active = active and (parent < 0 or go.m_IsActive)
        is_rect = transform.object_reader.type.name == "RectTransform"
        t, q, s = transform.m_LocalPosition, transform.m_LocalRotation, transform.m_LocalScale
        position = [t.x, t.y, t.z]
        if is_rect and not parent_is_rect:
            # A RectTransform under a plain Transform sits at its anchored position.
            position[:2] = [transform.m_AnchoredPosition.x, transform.m_AnchoredPosition.y]
        bone = {"name": go.m_Name, "parent": parent,
                "position": position, "rotation": [q.x, q.y, q.z, q.w], "scale": [s.x, s.y, s.z]}
        if at_origin:
            bone.update(position=[0, 0, 0], rotation=[0, 0, 0, 1], scale=[1, 1, 1])
        index = len(self.bones)
        self.bones.append(bone)
        self.index[transform.object_reader.path_id] = index
        self.active[go.object_reader.path_id] = active
        self.transforms[go.object_reader.path_id] = transform
        for child in transform.m_Children:
            self._walk(child.deref().read(), index, active, parent_is_rect=is_rect)


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

    def sprite(self, pptr):
        """A Sprite's own pixels (cut out of its texture), as a texture."""
        sprite = pptr.deref().read()
        name = file_name(sprite.m_Name) + "_sprite"
        if name not in self.textures:
            path = OUT / "textures" / "viewmodel" / f"{name}.png"
            path.parent.mkdir(parents=True, exist_ok=True)
            sprite.image.save(path)
            self.textures[name] = path
        return f"{NAMESPACE}:textures/viewmodel/{name}.png"

    def renderer_parts(self, renderer, mesh, joints, binds, influences):
        """One part per textured submesh: mesh-space vertices with the joints (bone and bind pose) they follow.

        `influences` is (joint indices, weights), one row per vertex.
        """
        handler = MeshHandler(mesh)
        handler.process()
        positions = np.array(handler.m_Vertices, dtype=float).reshape(-1, 3)
        normals = np.array(handler.m_Normals, dtype=float).reshape(-1, 3)
        uv = np.array(handler.m_UV0, dtype=float)
        joint_indices, weights = influences
        parts = []
        for i, triangles in enumerate(handler.get_triangles()):
            if i >= len(renderer.m_Materials) or not renderer.m_Materials[i].path_id:
                continue
            material = renderer.m_Materials[i].deref().read()
            tex_pptr, scale, offset = texture_name(material)
            if tex_pptr is None:
                continue
            texture, tex_file = self.texture(tex_pptr)
            emissive = self.emissive(renderer.m_Materials[i])
            used = sorted({v for tri in triangles for v in tri})
            remap = {v: n for n, v in enumerate(used)}
            u = uv[used, 0] * scale[0] + offset[0]
            v = 1 - (uv[used, 1] * scale[1] + offset[1])
            w = weights[used]
            count = max(1, int((w > 0).sum(axis=1).max()))
            order = np.argsort(-w, axis=1)[:, :count]
            top_joints = np.take_along_axis(joint_indices[used], order, axis=1)
            top_weights = np.take_along_axis(w, order, axis=1)
            top_weights = top_weights / top_weights.sum(axis=1, keepdims=True)
            parts.append({
                "texture": texture,
                "textureFile": tex_file,
                "material": material.m_Name,
                "positions": np.round(positions[used], 5).flatten().tolist(),
                "normals": np.round(normals[used], 4).flatten().tolist(),
                "uvs": np.round(np.stack([u, v], axis=1), 5).flatten().tolist(),
                # Reversed winding: the mod flips z, which changes handedness.
                "indices": [remap[x] for tri in triangles for x in (tri[0], tri[2], tri[1])],
                "joints": joints,
                # Column-major, as JOML reads them.
                "binds": [np.round(b.T.flatten(), 6).tolist() for b in binds],
                "influences": count,
                "jointIndices": top_joints.flatten().astype(int).tolist(),
                "jointWeights": np.round(top_weights, 4).flatten().tolist(),
            })
            if emissive:
                parts[-1]["emissive"] = emissive
        return parts

    def emissive(self, material_pptr):
        """The glow texture of a material that has ULTRAKILL/Master's EMISSIVE keyword, if any."""
        tree = material_pptr.deref().read_typetree()
        keywords = tree.get("m_ValidKeywords") or (tree.get("m_ShaderKeywords") or "").split()
        if "EMISSIVE" not in keywords:
            return None
        for key, env in material_pptr.deref().read().m_SavedProperties.m_TexEnvs:
            if key == "_EmissiveTex" and env.m_Texture.path_id:
                return self.texture(env.m_Texture)[0]
        return None


# Unity's built-in Quad mesh (in "Library/unity default resources"): 1 x 1, centered, facing -z.
BUILTIN_QUAD = 10210


def export_parts(exporter, rig, glow_colors):
    """The rig's meshes, and the built-in quads (screens, glows) as their bone and texture.

    `glow_colors` tints the glow of the renderers it names (by component path id).
    """
    parts = []
    quads = []
    sprites = []
    for go_id, active in rig.active.items():
        if not active:
            continue
        go = _objects[go_id].read()
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
                joints = [rig.index[b.path_id] for b in r.m_Bones]
                binds = [matrix(m) for m in mesh.m_BindPose]
                indices = np.array(handler.m_BoneIndices, dtype=int).reshape(handler.m_VertexCount, -1)
                if handler.m_BoneWeights is None:
                    weights = np.zeros(indices.shape)
                    weights[:, 0] = 1
                else:
                    weights = np.array(handler.m_BoneWeights, dtype=float).reshape(handler.m_VertexCount, -1)
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
                    if mesh_filter.m_Mesh.path_id == BUILTIN_QUAD and r.m_Materials and r.m_Materials[0].path_id:
                        tex_pptr, _, _ = texture_name(r.m_Materials[0].deref().read())
                        quads.append({
                            "name": go.m_Name,
                            "bone": rig.index[transform_of(go).object_reader.path_id],
                            "texture": exporter.texture(tex_pptr)[0] if tex_pptr else None,
                        })
                    continue
                handler = MeshHandler(mesh)
                handler.process()
                joints = [rig.index[transform_of(go).object_reader.path_id]]
                binds = [np.eye(4)]
                indices = np.zeros((handler.m_VertexCount, 1), dtype=int)
                weights = np.ones((handler.m_VertexCount, 1))
            elif kind == "SpriteRenderer":
                r = obj.read()
                if not r.m_Enabled or not r.m_Sprite.path_id:
                    continue
                tree = obj.read_typetree()
                sprite_tree = r.m_Sprite.deref().read_typetree()
                ppu = sprite_tree["m_PixelsToUnits"]
                width, height = sprite_tree["m_Rect"]["width"] / ppu, sprite_tree["m_Rect"]["height"] / ppu
                pivot = sprite_tree["m_Pivot"]
                color = tree["m_Color"]
                sprites.append({
                    "name": go.m_Name,
                    "bone": rig.index[transform_of(go).object_reader.path_id],
                    "texture": exporter.sprite(r.m_Sprite),
                    # left, bottom, right, top in the bone's space
                    "rect": [-pivot["x"] * width, -pivot["y"] * height, (1 - pivot["x"]) * width, (1 - pivot["y"]) * height],
                    "color": [color["r"], color["g"], color["b"], color["a"]],
                })
                continue
            else:
                continue
            for part in exporter.renderer_parts(r, mesh, joints, binds, (indices, weights)):
                part["name"] = go.m_Name
                if "emissive" in part and obj.path_id in glow_colors:
                    part["emissiveColor"] = glow_colors[obj.path_id]
                parts.append(part)
    return parts, quads, sprites


# UnityEngine.UI.Image.Type and FillMethod.
IMAGE_TYPES = {0: "simple", 1: "sliced", 2: "tiled", 3: "filled"}
FILL_METHODS = {0: "horizontal", 1: "vertical", 2: "radial90", 3: "radial180", 4: "radial360"}


def export_canvases(exporter, rig):
    """World-space canvases: each Image's rect in its canvas's units, laid out by RectTransform anchors.

    Returns the canvases and, by Image component path id, where each image landed (canvas, index).
    """
    canvases = []
    placed = {}
    for go_id, active in rig.active.items():
        if not active:
            continue
        go = _objects[go_id].read()
        canvas = next((c.component.deref() for c in go.m_Component if c.component.deref().type.name == "Canvas"), None)
        if canvas is None or canvas.read_typetree()["m_RenderMode"] != 2:
            continue
        scaler = next((c.component.deref().read_typetree() for c in go.m_Component
                       if c.component.deref().type.name == "MonoBehaviour" and script_name(c.component.deref()) == "CanvasScaler"), {})
        reference_ppu = scaler.get("m_ReferencePixelsPerUnit", 100)
        root = rig.transforms[go_id]
        size, pivot = root.m_SizeDelta, root.m_Pivot
        rect = (-pivot.x * size.x, -pivot.y * size.y, (1 - pivot.x) * size.x, (1 - pivot.y) * size.y)
        images = []

        def layout(transform, parent_rect):
            x0, y0, x1, y1 = parent_rect
            w, h = x1 - x0, y1 - y0
            amin, amax, pos, delta, piv = transform.m_AnchorMin, transform.m_AnchorMax, transform.m_AnchoredPosition, transform.m_SizeDelta, transform.m_Pivot
            width = (amax.x - amin.x) * w + delta.x
            height = (amax.y - amin.y) * h + delta.y
            px = x0 + (amin.x + (amax.x - amin.x) * piv.x) * w + pos.x
            py = y0 + (amin.y + (amax.y - amin.y) * piv.y) * h + pos.y
            return (px - piv.x * width, py - piv.y * height, px + (1 - piv.x) * width, py + (1 - piv.y) * height)

        def walk(transform, own_rect):
            child_go = transform.m_GameObject.read()
            if not rig.active.get(child_go.object_reader.path_id):
                return
            for c in child_go.m_Component:
                obj = c.component.deref()
                if obj.type.name != "MonoBehaviour" or script_name(obj) != "Image":
                    continue
                tree = obj.read_typetree()
                image = obj.read()
                if not tree["m_Enabled"] or not image.m_Sprite.path_id:
                    continue
                sprite = image.m_Sprite.deref().read()
                sprite_tree = image.m_Sprite.deref().read_typetree()
                # Sliced borders are in sprite pixels; the canvas draws them at its reference pixels per unit.
                units = reference_ppu / sprite_tree["m_PixelsToUnits"] / tree["m_PixelsPerUnitMultiplier"]
                border = sprite_tree["m_Border"]
                color = tree["m_Color"]
                placed[obj.path_id] = (len(canvases), len(images))
                images.append({
                    "name": child_go.m_Name,
                    "rect": [round(v, 6) for v in own_rect],
                    "texture": exporter.texture(sprite.m_RD.texture)[0],
                    "color": [color["r"], color["g"], color["b"], color["a"]],
                    "type": IMAGE_TYPES[tree["m_Type"]],
                    "border": [border["x"] * units, border["y"] * units, border["z"] * units, border["w"] * units],
                    "uvBorder": [border["x"] / sprite_tree["m_Rect"]["width"], border["y"] / sprite_tree["m_Rect"]["height"],
                                 border["z"] / sprite_tree["m_Rect"]["width"], border["w"] / sprite_tree["m_Rect"]["height"]],
                    "fillMethod": FILL_METHODS[tree["m_FillMethod"]],
                    "fillOrigin": tree["m_FillOrigin"],
                    "fillClockwise": bool(tree["m_FillClockwise"]),
                    "fillAmount": tree["m_FillAmount"],
                })
            for child in transform.m_Children:
                t = child.deref().read()
                walk(t, layout(t, own_rect))

        walk(root, rect)
        canvases.append({"bone": rig.index[root.object_reader.path_id], "images": images})
    return canvases, placed


def coin_flash(exporter, flash_go):
    """The coin's flash (Coin.flash): a sprite facing the camera, with its size in the flash's space."""
    scale = 1.0

    def find(go, scale):
        transform = transform_of(go)
        scale *= transform.m_LocalScale.x
        for c in go.m_Component:
            obj = c.component.deref()
            if obj.type.name == "SpriteRenderer":
                renderer = obj.read()
                sprite = renderer.m_Sprite.deref().read_typetree()
                size = sprite["m_Rect"]["width"] / sprite["m_PixelsToUnits"] * scale
                return {"texture": exporter.sprite(renderer.m_Sprite), "size": size, "alpha": obj.read_typetree()["m_Color"]["a"]}
        for child in transform.m_Children:
            found = find(child.deref().read().m_GameObject.read(), scale)
            if found:
                return found
        return None

    return find(flash_go, scale)


def go_name_of(component_pptr):
    """The name of the GameObject a referenced component sits on, or None."""
    return component_pptr.deref().read().m_GameObject.read().m_Name if component_pptr.path_id else None


def color_list(color):
    return [color.r, color.g, color.b, color.a]


def export_scripts(exporter, rig, colors, placed):
    """Values from ULTRAKILL scripts on the model that the mod's ports of them need."""
    scripts = {}
    for go_id, active in rig.active.items():
        go = _objects[go_id].read()
        for c in go.m_Component:
            obj = c.component.deref()
            if obj.type.name != "MonoBehaviour":
                continue
            kind = script_name(obj)
            if kind == "Revolver":
                revolver = obj.read()
                tree = obj.read_typetree()
                texture = lambda p: exporter.texture(p)[0] if p.path_id else None  # noqa: E731
                scripts["revolver"] = {
                    "variation": tree["gunVariation"],
                    "variationColor": list(colors[tree["gunVariation"]]),
                    "batteryLow": texture(revolver.batteryLow),
                    "batteryMid": texture(revolver.batteryMid),
                    "batteryFull": texture(revolver.batteryFull),
                    "batteryCharges": [texture(p) for p in revolver.batteryCharges],
                    "coinPanels": [list(placed[p.path_id]) for p in revolver.coinPanels],
                    "twirlBone": rig.index[revolver.twirlBone.path_id] if revolver.twirlBone.path_id else None,
                    "twirlSprite": go_name_of(revolver.twirlSprite),
                }
            elif kind == "Coin":
                scripts["coin"] = {"flash": coin_flash(exporter, obj.read().flash.deref().read())}
            elif kind == "RevolverCylinder" and active:
                tree = obj.read_typetree()
                axis = tree["rotationAxis"]
                scripts["cylinder"] = {
                    "bone": rig.index[transform_of(go).object_reader.path_id],
                    "bulletAmount": tree["bulletAmount"],
                    "rotationAxis": [axis["x"], axis["y"], axis["z"]],
                    "speed": tree["speed"],
                }
    return scripts


def find_animator(rig):
    """The first Animator in the prefab, depth first."""
    for go_id in rig.active:
        go = _objects[go_id].read()
        for c in go.m_Component:
            if c.component.deref().type.name == "Animator":
                return c.component.deref().read()
    return None


def clip_curves(clip):
    """Every curve of a clip, in binding order (streamed, dense, then constant), as cubic segments.

    A segment starting at `time` with coefficients (a, b, c, d) has the value ((a*t + b)*t + c)*t + d, t seconds
    after its start, until the curve's next segment.
    """
    data = clip.m_MuscleClip.m_Clip
    data = getattr(data, "data", data)
    streamed = data.m_StreamedClip
    curves = [[] for _ in range(streamed.curveCount)]
    raw = struct.pack(f"<{len(streamed.data)}I", *streamed.data)
    pos = 0
    while pos < len(raw):
        time, count = struct.unpack_from("<fi", raw, pos)
        pos += 8
        for i in range(count):
            index, *coefficients = struct.unpack_from("<i4f", raw, pos + i * 20)
            curves[index].append((time, coefficients))
        pos += count * 20
    # Each curve starts with a key at -FLT_MAX: the value it holds before its first real key.
    for keys in curves:
        if keys and keys[0][0] < 0:
            if len(keys) > 1 and keys[1][0] <= 0:
                keys.pop(0)
            else:
                keys[0] = (0.0, [0.0, 0.0, 0.0, keys[0][1][3]])
    dense = data.m_DenseClip
    if dense.m_CurveCount:
        samples = np.array(dense.m_SampleArray, dtype=float).reshape(-1, dense.m_CurveCount)
        for curve in range(dense.m_CurveCount):
            keys = []
            for frame in range(dense.m_FrameCount):
                value = samples[frame, curve]
                following = samples[min(frame + 1, dense.m_FrameCount - 1), curve]
                keys.append((dense.m_BeginTime + frame / dense.m_SampleRate, [0.0, 0.0, (following - value) * dense.m_SampleRate, value]))
            curves.append(keys)
    for value in data.m_ConstantClip.data:
        curves.append([(0.0, [0.0, 0.0, 0.0, value])])
    return curves


def export_clip(clip_pptr, paths):
    clip = clip_pptr.deref().read()
    tree = clip_pptr.deref().read_typetree()
    curves = clip_curves(clip)
    channels = []
    index = 0
    for binding in clip.m_ClipBindingConstant.genericBindings:
        prop, size = TRANSFORM_PROPERTIES.get(binding.attribute, (None, 1)) if binding.typeID == TRANSFORM_CLASS else (None, 1)
        bone = paths.get(binding.path)
        if prop == "euler":
            sys.exit(f"{clip.m_Name}: euler rotation curves aren't supported")
        if prop is not None and bone is not None:
            for component in range(size):
                keys = curves[index + component]
                channels.append({
                    "bone": bone,
                    "property": prop,
                    "component": component,
                    "times": [k[0] for k in keys],
                    "coefficients": [x for k in keys for x in k[1]],
                })
        index += size
    muscle = clip.m_MuscleClip
    return {
        "name": clip.m_Name,
        "length": muscle.m_StopTime - muscle.m_StartTime,
        "loop": bool(muscle.m_LoopTime),
        "events": [{"time": e["time"], "function": e["functionName"]} for e in tree.get("m_Events", [])],
        "curves": channels,
    }


def export_controller(controller_obj):
    """The base layer's state machine: Animator layers above it only play in ULTRAKILL's shop."""
    tree = controller_obj.read_typetree()
    names = {h: n for h, n in tree["m_TOS"]}
    c = tree["m_Controller"]
    defaults = c["m_DefaultValues"]["data"]

    def default(value):
        kind = PARAMETER_TYPES[value["m_Type"]]
        array = {"float": "m_FloatValues", "int": "m_IntValues"}.get(kind, "m_BoolValues")
        return defaults[array][value["m_Index"]]

    parameters = [{"name": names[v["m_ID"]], "type": PARAMETER_TYPES[v["m_Type"]], "default": default(v)}
                  for v in c["m_Values"]["data"]["m_ValueArray"]]
    layer = c["m_LayerArray"][0]["data"]
    machine = c["m_StateMachineArray"][layer["m_StateMachineIndex"]]["data"]
    state_count = len(machine["m_StateConstantArray"])

    def transition(t):
        t = t["data"]
        if t["m_DestinationState"] >= state_count:
            sys.exit(f"{names.get(t['m_FullPathID'])}: transitions to the exit node aren't supported")
        conditions = []
        for condition in t["m_ConditionConstantArray"]:
            condition = condition["data"]
            mode = CONDITION_MODES.get(condition["m_ConditionMode"])
            if mode is None:
                sys.exit(f"condition mode {condition['m_ConditionMode']} isn't supported")
            conditions.append({"mode": mode, "parameter": names[condition["m_EventID"]], "threshold": condition["m_EventThreshold"]})
        return {
            "destination": t["m_DestinationState"],
            "conditions": conditions,
            "duration": t["m_TransitionDuration"],
            "offset": t["m_TransitionOffset"],
            "exitTime": t["m_ExitTime"],
            "hasExitTime": t["m_HasExitTime"],
            "fixedDuration": t["m_HasFixedDuration"],
            "interruptionSource": t["m_InterruptionSource"],
            "orderedInterruption": t["m_OrderedInterruption"],
            "canTransitionToSelf": t["m_CanTransitionToSelf"],
        }

    states = []
    for state in machine["m_StateConstantArray"]:
        state = state["data"]
        trees = state["m_BlendTreeConstantArray"]
        nodes = trees[0]["data"]["m_NodeArray"] if trees else []
        if len(nodes) != 1:
            sys.exit(f"{names[state['m_NameID']]}: blend trees aren't supported")
        states.append({
            "name": names[state["m_NameID"]],
            "clip": clip if (clip := nodes[0]["data"]["m_ClipID"]) != 0xFFFFFFFF else -1,
            "speed": state["m_Speed"],
            "speedParameter": names.get(state["m_SpeedParamID"]) or None,
            "cycleOffset": state["m_CycleOffset"],
            "transitions": [transition(t) for t in state["m_TransitionConstantArray"]],
        })
    return {
        "parameters": parameters,
        "defaultState": machine["m_DefaultState"],
        "states": states,
        "anyStateTransitions": [transition(t) for t in machine["m_AnyStateTransitionConstantArray"]],
    }


def binding_paths(transform, rig):
    """Bone index by the CRC32 of each transform's path under `transform`, as clip bindings name them."""
    paths = {}

    def walk(t, path):
        paths[zlib.crc32(path.encode())] = rig.index[t.object_reader.path_id]
        for child in t.m_Children:
            c = child.deref().read()
            name = c.m_GameObject.read().m_Name
            walk(c, f"{path}/{name}" if path else name)

    walk(transform, "")
    return paths


def export_animator(animator, rig):
    """The Animator's controller and clips, with curves bound to bones.

    Clip paths are relative to the transform the clips were authored on: the Animator's own, or (the
    Feedbacker's) a child that carries the rig. Each candidate under the Animator is tried, and the one that
    binds the most curves wins.
    """
    controller = animator.m_Controller.deref()
    clips = controller.read().m_AnimationClips
    wanted = {b.path for c in clips for b in c.deref().read().m_ClipBindingConstant.genericBindings}
    candidates = []

    def collect(t):
        if rig.active.get(t.m_GameObject.path_id):
            candidates.append(binding_paths(t, rig))
        for child in t.m_Children:
            collect(child.deref().read())

    collect(transform_of(animator.m_GameObject.read()))
    paths = max(candidates, key=lambda p: len(wanted & p.keys()))
    return {
        "controller": export_controller(controller),
        "clips": [export_clip(c, paths) for c in clips],
    }


def variation_glow(root_go, colors):
    """WeaponIcon tints its variationColoredRenderers' glow (_EmissiveColor) with the weapon's variation color."""
    for c in root_go.m_Component:
        obj = c.component.deref()
        if obj.type.name == "MonoBehaviour" and script_name(obj) == "WeaponIcon":
            icon = obj.read()
            color = list(colors[icon.weaponDescriptor.deref().read().variationColor])
            return {r.path_id: color for r in icon.variationColoredRenderers}
    return {}


def export_model(exporter, root_go, offset, colors):
    rig = Rig(root_go, at_origin=offset is None)
    parts, quads, sprites = export_parts(exporter, rig, variation_glow(root_go, colors))
    canvases, placed = export_canvases(exporter, rig)
    model = {"origin": list(offset or (0, 0, 0)), "bones": rig.bones, "parts": parts, "quads": quads, "sprites": sprites,
             "canvases": canvases, "scripts": export_scripts(exporter, rig, colors, placed)}
    animator = find_animator(rig)
    if animator is not None:
        model["animator"] = export_animator(animator, rig)
    return model


_objects = {}


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
        for model, (root_name, script, offset) in MODELS.items():
            if model in found or cls != script:
                continue
            go = obj.read().m_GameObject.read()
            if go.m_Name != root_name:
                continue
            tree = obj.read_typetree()
            if tree.get("altVersion"):
                continue
            found[model] = (go, offset)
    return found


def evaluate_clip(model, clip, time):
    """Each bone's local matrix with a clip applied at `time` seconds, as the mod evaluates it."""
    import bisect
    values = [{k: list(b[k]) for k in ("position", "rotation", "scale")} for b in model["bones"]]
    for channel in clip["curves"]:
        times = channel["times"]
        k = max(bisect.bisect_right(times, time) - 1, 0)
        a, b, c, d = channel["coefficients"][4 * k:4 * k + 4]
        t = time - times[k]
        values[channel["bone"]][channel["property"]][channel["component"]] = ((a * t + b) * t + c) * t + d
    out = []
    for v in values:
        q = np.array(v["rotation"])
        out.append(local_matrix(v["position"], q / np.linalg.norm(q), v["scale"]))
    return out


def skinned(model, locals_):
    """The model's parts with vertices in camera space (x right, y up, -z forward), for previews."""
    world = []
    origin = np.eye(4)
    origin[:3, 3] = model["origin"]
    for bone, local in zip(model["bones"], locals_):
        world.append((origin if bone["parent"] < 0 else world[bone["parent"]]) @ local)
    parts = []
    for part in model["parts"]:
        skins = [world[j] @ np.array(b).reshape(4, 4).T for j, b in zip(part["joints"], part["binds"])]
        k = part["influences"]
        positions = np.array(part["positions"]).reshape(-1, 3)
        normals = np.array(part["normals"]).reshape(-1, 3)
        joints = np.array(part["jointIndices"]).reshape(-1, k)
        weights = np.array(part["jointWeights"]).reshape(-1, k)
        out_p = np.zeros_like(positions)
        out_n = np.zeros_like(normals)
        for i in range(k):
            m = np.array([skins[j] for j in joints[:, i]])
            out_p += weights[:, i:i + 1] * (np.einsum("nij,nj->ni", m[:, :3, :3], positions) + m[:, :3, 3])
            out_n += weights[:, i:i + 1] * np.einsum("nij,nj->ni", m[:, :3, :3], normals)
        flip = np.array([1, 1, -1])
        parts.append({**part, "positions": (out_p * flip).flatten(), "normals": (out_n * flip).flatten()})
    return parts


def rest_locals(model):
    return [local_matrix(b["position"], b["rotation"], b["scale"]) for b in model["bones"]]


def render(parts, width=480, height=270):
    """The parts as the camera sees them (90 degree vertical FOV), flat shaded."""
    from PIL import Image, ImageDraw
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
            n = nrm[[a, b, c]].mean(axis=0)
            n = n / (np.linalg.norm(n) or 1)
            shade = 0.45 + 0.55 * max(0.0, float(np.dot(n, [0.3, 0.8, 0.5])))
            screen = [(width / 2 + focal * x / -z, height / 2 - focal * y / -z) for x, y, z in p]
            tris.append((p[:, 2].mean(), screen, tuple((color * shade).clip(0, 255).astype(int))))
    for _, screen, color in sorted(tris, key=lambda t: t[0]):
        draw.polygon(screen, fill=color)
    return image


def preview(name, model, directory):
    """The rest pose, and for animated models a sheet of every clip at five points in its length."""
    from PIL import Image, ImageDraw
    directory.mkdir(parents=True, exist_ok=True)
    render(skinned(model, rest_locals(model))).save(directory / f"{name}.png")
    if "animator" not in model:
        return
    clips = model["animator"]["clips"]
    steps = 5
    sheet = Image.new("RGB", (480 * steps, 270 * len(clips)))
    for row, clip in enumerate(clips):
        for col in range(steps):
            time = clip["length"] * col / (steps - 1)
            image = render(skinned(model, evaluate_clip(model, clip, time)))
            ImageDraw.Draw(image).text((6, 6), f"{clip['name']} {time:.2f}s", fill=(255, 255, 255))
            sheet.paste(image, (col * 480, row * 270))
    sheet.save(directory / f"{name}_clips.png")


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
    parser.add_argument("--preview", type=Path, help="also render previews of each model and its clips into this folder")
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
    for name, (root, offset) in roots.items():
        export_icon(root, name, colors)
        model = export_model(exporter, root, offset, colors)
        (OUT / "viewmodels" / f"{name}.json").write_text(json.dumps(model))
        parts = model["parts"]
        clips = [c["name"] for c in model.get("animator", {}).get("clips", [])]
        print(f"{name}: {len(model['bones'])} bones, {len(parts)} parts, {sum(len(p['indices']) // 3 for p in parts)} triangles, "
              f"quads {[q['name'] for q in model['quads']]}, sprites {[q['name'] for q in model['sprites']]}, canvas images {[len(c['images']) for c in model['canvases']]}, "
              f"scripts {sorted(model['scripts'])}, clips {clips}")
        if args.preview:
            preview(name, model, args.preview)
    print(f"{len(exporter.textures)} textures -> {OUT.relative_to(REPO)}")


if __name__ == "__main__":
    main()
