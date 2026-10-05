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
from UnityPy.helpers.ResourceReader import get_resource_data

sys.path.insert(0, str(Path(__file__).parent))
from ultrakill_paths import BUNDLES, REPO  # noqa: E402
from ultrakill_surfaces import SURFACES  # noqa: E402

OUT = REPO / "mod" / "src" / "generated" / "resources" / "assets" / "ultracraft"
NAMESPACE = "ultracraft"

# Model name -> (prefab root name, script on the root, the root's parent offset from the camera).
# Guns hang directly off the camera; the arm hangs off FistControl, 0.225 units below it. Models drawn in the
# world have no offset and are written relative to their own root.
MODELS = {
    "revolver_piercer": ("Revolver Pierce", "Revolver", (0, 0, 0)),
    "revolver_marksman": ("Revolver Ricochet", "Revolver", (0, 0, 0)),
    "revolver_sharpshooter": ("Revolver Twirl", "Revolver", (0, 0, 0)),
    "shotgun_core_eject": ("Shotgun Grenade", "Shotgun", (0, 0, 0)),
    "shotgun_pump_charge": ("Shotgun Pump", "Shotgun", (0, 0, 0)),
    "shotgun_sawed_on": ("Shotgun Saw", "Shotgun", (0, 0, 0)),
    "railcannon_electric": ("Railcannon Electric", "Railcannon", (0, 0, 0)),
    "railcannon_screwdriver": ("Railcannon Harpoon", "Railcannon", (0, 0, 0)),
    "railcannon_malicious": ("Railcannon Malicious", "Railcannon", (0, 0, 0)),
    "nailgun_attractor": ("Nailgun Magnet", "Nailgun", (0, 0, 0)),
    "nailgun_overheat": ("Nailgun Overheat", "Nailgun", (0, 0, 0)),
    "nailgun_jumpstart": ("Nailgun Zapper", "Nailgun", (0, 0, 0)),
    "feedbacker": ("Arm Blue", "Punch", (0, -0.225, 0)),
    "knuckleblaster": ("Arm Red", "Punch", (0, -0.225, 0)),
    "whiplash": ("Hook Arm", "HookArm", (0, -0.225, 0)),
    "coin": ("Coin", "Coin", None),
    "shotgun_shell": ("ShotgunShell", "ShotgunShell", None),
    "core": ("Grenade", "Grenade", None),
    "explosion_wave_knuckleblaster": ("Explosion Wave Knuckleblaster", "RandomPitch", None),
}

# Models drawn in the world that are only reachable through another prefab's field:
# name -> (script, the GameObject it's on, the field naming the prefab). Prefabs named from inside another
# referenced model: name -> (that model, script, field).
REFERENCED = {
    "explosion": ("Punch", "Arm Blue", "parriedProjectileHitObject"),
    "shotgun_muzzle_flash": ("Shotgun", "Shotgun Grenade", "muzzleFlash"),
    "bullet_spark": ("Projectile", "Shotgun Projectile", "explosionEffect"),
    "explosion_super": ("Grenade", "Grenade", "superExplosion"),
    "chainsaw_break": ("Shotgun", "Shotgun Saw", "chainsawBreakEffect"),
    "chainsaw_thrown": ("Shotgun", "Shotgun Saw", "chainsaw"),
    "slam_shockwave": ("GroundCheck", "GroundCheck", "shockwave"),
    "whiplash_clink": ("HookArm", "Hook Arm", "clinkSparks"),
    "whiplash_clink_object": ("HookArm", "Hook Arm", "clinkObjectSparks"),
    "railcannon_beam": ("Railcannon", "Railcannon Electric", "beam"),
    "railcannon_beam_malicious": ("Railcannon", "Railcannon Malicious", "beam"),
    "drill": ("Railcannon", "Railcannon Harpoon", "beam"),
    "nail": ("Nailgun", "Nailgun Overheat", "nail"),
    "nail_fodder": ("Nailgun", "Nailgun Magnet", "nail"),
    "nail_heated": ("Nailgun", "Nailgun Overheat", "heatedNail"),
    "magnet": ("Nailgun", "Nailgun Magnet", "magnetNail"),
    "nailgun_muzzle_flash": ("Nailgun", "Nailgun Overheat", "muzzleFlash"),
    "nailgun_muzzle_flash_heated": ("Nailgun", "Nailgun Overheat", "muzzleFlash2"),
    "zapper": ("Nailgun", "Nailgun Zapper", "zapper"),
    "zap_sparks": ("Zapper", "Zapper", "zapParticle"),
    "electric_line": ("DefaultReferenceManager", "DefaultReferenceManager", "electricLine"),
    "slide_particle": ("NewMovement", "Player", "slideParticle"),
}
# FootstepSet's per-surface prefabs: model name prefix -> the list field. Each surface it lists is written as
# <prefix>_<surface>.
FOOTSTEP_PARTICLES = {"slide_scrape": "slideParticles", "wall_scrape": "wallScrapeParticles"}
NESTED = {
    "explosion_chunk": ("explosion", "Explosion", "explosionChunk"),
    "chainsaw_ricochet": ("chainsaw_thrown", "Chainsaw", "ricochetEffect"),
    "chainsaw_sawblade": ("chainsaw_thrown", "Chainsaw", "sawbladeVersion"),
    "explosion_malicious_railcannon": ("railcannon_beam_malicious", "RevolverBeam", "hitParticle"),
    "drill_beeper": ("drill", "TimeBomb", "beepLight"),
    "drill_break": ("drill", "TimeBomb", "explosion"),
    "magnet_beeper": ("magnet", "TimeBomb", "beepLight"),
    "magnet_break": ("magnet", "TimeBomb", "explosion"),
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


def activate_controlled(rig, root_go):
    """An ExplosionController turns on its toActivate objects when it starts, unless simple explosions are on."""
    for c in root_go.m_Component:
        obj = c.component.deref()
        if obj.type.name != "MonoBehaviour" or script_name(obj) != "ExplosionController":
            continue
        for target in obj.read().toActivate:
            if not target.path_id:
                continue

            def walk(t):
                rig.active[t.m_GameObject.path_id] = True
                for child in t.m_Children:
                    walk(child.deref().read())

            walk(transform_of(target.deref().read()))


def activate_scripted(rig, root_go):
    """Objects the zapper and the JumpStart show and hide themselves, which their prefabs store hidden: the zapper's
    closed prongs and its pulse orb, and the JumpStart's recharge overlay."""
    def walk(t):
        rig.active[t.m_GameObject.path_id] = True
        for child in t.m_Children:
            walk(child.deref().read())

    for go_id in list(rig.active):
        go = _objects[go_id].read()
        for c in go.m_Component:
            obj = c.component.deref()
            if obj.type.name != "MonoBehaviour":
                continue
            kind = script_name(obj)
            if kind == "Zapper":
                zapper = obj.read()
                for pptr in (zapper.openProngs, zapper.closedProngs):
                    walk(transform_of(pptr.deref().read()))
                walk(zapper.lightningPulseOrb.deref().read())
            elif kind == "Nailgun" and obj.read().rechargingOverlay.path_id:
                walk(transform_of(obj.read().rechargingOverlay.deref().read()))


def code_shown_images(rig):
    """Images a script turns on and off itself, which their prefabs store turned off: the JumpStart's warning X."""
    shown = set()
    for go_id in rig.active:
        for c in _objects[go_id].read().m_Component:
            obj = c.component.deref()
            if obj.type.name == "MonoBehaviour" and script_name(obj) == "Nailgun" and obj.read().warningX.path_id:
                shown.add(obj.read().warningX.path_id)
    return shown


def referenced_roots(env):
    """The REFERENCED prefabs, found through the field that names them."""
    found = {}
    for obj in env.objects:
        if obj.type.name != "MonoBehaviour":
            continue
        cls = script_name(obj)
        for model, (script, go_name, field) in REFERENCED.items():
            if model in found or cls != script:
                continue
            component = obj.read()
            if component.m_GameObject.read().m_Name != go_name:
                continue
            found[model] = (prefab(getattr(component, field)), None)
    for model, (parent, script, field) in NESTED.items():
        def walk(t):
            for c in t.m_GameObject.read().m_Component:
                obj = c.component.deref()
                if obj.type.name == "MonoBehaviour" and script_name(obj) == script and getattr(obj.read(), field).path_id:
                    return prefab(getattr(obj.read(), field))
            for child in t.m_Children:
                result = walk(child.deref().read())
                if result is not None:
                    return result
            return None

        found[model] = (walk(transform_of(found[parent][0])), None)
    footsteps = next(obj.read() for obj in env.objects if obj.type.name == "MonoBehaviour" and script_name(obj) == "FootstepSet")
    for prefix, field in FOOTSTEP_PARTICLES.items():
        for entry in getattr(footsteps, field):
            surface = SURFACES[getattr(entry, "<SurfaceType>k__BackingField")]
            found[f"{prefix}_{surface}"] = (prefab(getattr(entry, "<particle>k__BackingField")), None)
    return found


def prefab(pptr):
    """The GameObject a prefab field names, whether it's typed as the object or as one of its components."""
    target = pptr.deref().read()
    return target if target.object_reader.type.name == "GameObject" else target.m_GameObject.read()


def transform_of(go):
    return next(c.component.deref().read() for c in go.m_Component if c.component.deref().type.name in ("Transform", "RectTransform"))


class Rig:
    """Every transform under a prefab root, depth first, with its parent and the prefab's local values."""

    def __init__(self, root_go, at_origin):
        """`at_origin` puts the root itself at the origin, unrotated, instead of where the prefab stores it."""
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
            bone.update(position=[0, 0, 0], rotation=[0, 0, 0, 1])
        index = len(self.bones)
        self.bones.append(bone)
        self.index[transform.object_reader.path_id] = index
        self.active[go.object_reader.path_id] = active
        self.transforms[go.object_reader.path_id] = transform
        for child in transform.m_Children:
            self._walk(child.deref().read(), index, active, parent_is_rect=is_rect)


def material_values(material_pptr, texture_path):
    """What the mod needs to draw a material its own way: its colors, keywords and blending, and its texture's mean alpha."""
    from PIL import Image
    tree = material_pptr.deref().read_typetree()
    props = tree["m_SavedProperties"]
    colors = {k: [v["r"], v["g"], v["b"], v["a"]] for k, v in props["m_Colors"] if k in ("_Color", "_EmissionColor", "_TintColor")}
    floats = {k: v for k, v in props["m_Floats"] if k in ("_SrcBlend", "_DstBlend", "_BlendMode", "_Mode", "_Cull", "_CullMode")}
    alpha = np.array(Image.open(texture_path).convert("RGBA"))[..., 3].mean() / 255 if texture_path else 1
    return {"colors": colors, "floats": floats, "keywords": tree.get("m_ValidKeywords") or [], "textureAlpha": round(float(alpha), 4)}


def curve_value(curve):
    """A ParticleSystem MinMaxCurve: a constant, a range between two constants, or a curve's keys times its scalar."""
    state = curve["minMaxState"]
    if state == 0:
        return {"constant": curve["scalar"]}
    if state == 3:
        return {"min": curve["minScalar"], "max": curve["scalar"]}
    return {"scalar": curve["scalar"], "keys": [[k["time"], k["value"]] for k in curve["maxCurve"]["m_Curve"]]}


def gradient_value(gradient):
    return {
        "colors": [[gradient[f"ctime{i}"] / 65535, gradient[f"key{i}"]["r"], gradient[f"key{i}"]["g"], gradient[f"key{i}"]["b"]] for i in range(gradient["m_NumColorKeys"])],
        "alphas": [[gradient[f"atime{i}"] / 65535, gradient[f"key{i}"]["a"]] for i in range(gradient["m_NumAlphaKeys"])],
    }


def color_value(color):
    """A ParticleSystem MinMaxGradient: a color, a gradient, or a range between two colors."""
    state = color["minMaxState"]
    rgba = lambda c: [c["r"], c["g"], c["b"], c["a"]]  # noqa: E731
    if state == 0:
        return {"color": rgba(color["maxColor"])}
    if state == 2:
        return {"min": rgba(color["minColor"]), "max": rgba(color["maxColor"])}
    return {"gradient": gradient_value(color["maxGradient"])}


def particle_values(system):
    """A ParticleSystem's settings, as far as the mod's particles go."""
    t = system.read_typetree()
    main, emission, shape = t["InitialModule"], t["EmissionModule"], t["ShapeModule"]
    values = {
        "duration": t["lengthInSec"], "looping": bool(t["looping"]), "scalingMode": t["scalingMode"],
        "simulationSpace": t["moveWithTransform"],
        "lifetime": curve_value(main["startLifetime"]), "speed": curve_value(main["startSpeed"]),
        "size": curve_value(main["startSize"]), "color": color_value(main["startColor"]),
        "gravity": curve_value(main["gravityModifier"]), "rotation": curve_value(main["startRotation"]),
        "rate": curve_value(emission["rateOverTime"]),
        "bursts": [[b["time"], curve_value(b["countCurve"])] for b in emission["m_Bursts"]],
        "shape": {"type": shape["type"], "radius": shape["radius"]["value"], "angle": shape["angle"],
                  "thickness": shape["radiusThickness"],
                  **{key: [shape[field]["x"], shape[field]["y"], shape[field]["z"]] for key, field in
                     (("position", "m_Position"), ("rotation", "m_Rotation"), ("scale", "m_Scale"))}} if shape["enabled"] else None,
    }
    if t["SizeModule"]["enabled"]:
        values["sizeOverLifetime"] = curve_value(t["SizeModule"]["curve"])
    if t["ColorModule"]["enabled"]:
        values["colorOverLifetime"] = color_value(t["ColorModule"]["gradient"])
    trail = t["TrailModule"]
    if trail["enabled"]:
        values["trail"] = {"ratio": trail["ratio"], "lifetime": curve_value(trail["lifetime"]),
                           "widthOverTrail": curve_value(trail["widthOverTrail"]),
                           "colorOverLifetime": color_value(trail["colorOverLifetime"]),
                           "sizeAffectsWidth": bool(trail["sizeAffectsWidth"]),
                           "inheritParticleColor": bool(trail["inheritParticleColor"])}
    return values


def animated_texture(exporter, go):
    """The object's AnimatedTexture, if it swaps its renderer's texture between an array's slices by itself:
    the slices, how often it swaps, and whether to a random one."""
    for c in go.m_Component:
        obj = c.component.deref()
        if obj.type.name == "MonoBehaviour" and script_name(obj) == "AnimatedTexture":
            script = obj.read()
            tree = obj.read_typetree()
            if tree["manualTrigger"] or not script.arrayTex.path_id or tree["textureType"] != 0:
                return {}
            return {"animatedTexture": {"frames": exporter.texture_array(script.arrayTex), "delay": tree["delay"],
                                        "randomFrame": bool(tree["randomFrame"])}}
    return {}


def texture_name(material):
    for key, env in material.m_SavedProperties.m_TexEnvs:
        if key == "_MainTex" and env.m_Texture.path_id:
            return env.m_Texture, (env.m_Scale.x, env.m_Scale.y), (env.m_Offset.x, env.m_Offset.y)
    return None, (1, 1), (0, 0)


def file_name(name):
    return re.sub(r"[^a-z0-9_]", "_", name.lower()).strip("_")


# Minecraft draws a TrueType font this many of its text units to the em, rasterized this many times finer.
FONT_SIZE = 9
FONT_OVERSAMPLE = 8


class Exporter:
    def __init__(self, ttf_fonts):
        self.textures = {}
        self.fonts = {}
        # The game's fonts by file name, as pointers to them.
        self.ttf_fonts = ttf_fonts

    def texture(self, pptr):
        tex = pptr.deref().read()
        name = file_name(tex.m_Name)
        if name not in self.textures:
            path = OUT / "textures" / "viewmodel" / f"{name}.png"
            path.parent.mkdir(parents=True, exist_ok=True)
            tex.image.save(path)
            self.textures[name] = path
        return f"{NAMESPACE}:textures/viewmodel/{name}.png", name

    def texture_array(self, pptr):
        """Each slice of an uncompressed RGBA Texture2DArray, as its own texture."""
        array = pptr.deref()
        tree = array.read_typetree()
        name = file_name(tree["m_Name"])
        width, height, depth = tree["m_Width"], tree["m_Height"], tree["m_Depth"]
        data = array.read().image_data
        if not data:
            stream = tree["m_StreamData"]
            data = get_resource_data(stream["path"], array.assets_file, stream["offset"], stream["size"])
        if len(data) != width * height * 4 * depth:
            sys.exit(f"{tree['m_Name']}: not an uncompressed RGBA array")
        from PIL import Image
        names = []
        for i in range(depth):
            frame = f"{name}_{i}"
            if frame not in self.textures:
                path = OUT / "textures" / "viewmodel" / f"{frame}.png"
                size = width * height * 4
                # Unity stores rows bottom up.
                Image.frombytes("RGBA", (width, height), bytes(data[i * size:(i + 1) * size])).transpose(Image.FLIP_TOP_BOTTOM).save(path)
                self.textures[frame] = path
            names.append(f"{NAMESPACE}:textures/viewmodel/{frame}.png")
        return names

    def font(self, pptr):
        """A UI Text's font, as a Minecraft TrueType font: its TTF under font/, and a definition naming it."""
        font = (pptr.deref() if callable(getattr(pptr, "deref", None)) else pptr).read()
        name = file_name(font.m_Name)
        if name not in self.fonts:
            folder = OUT / "font"
            folder.mkdir(parents=True, exist_ok=True)
            (folder / f"{name}.ttf").write_bytes(bytes(font.m_FontData))
            definition = {"providers": [{"type": "ttf", "file": f"{NAMESPACE}:{name}.ttf", "size": FONT_SIZE, "oversample": FONT_OVERSAMPLE}]}
            (folder / f"{name}.json").write_text(json.dumps(definition, indent=1))
            self.fonts[name] = True
        return f"{NAMESPACE}:{name}"

    def tmp_font(self, pptr):
        """A TextMeshPro font asset's font, as a Minecraft TrueType font. Its source font isn't in the game, so this
        is the game's TrueType font of the same name."""
        name = file_name(pptr.deref().read_typetree()["m_Name"])
        font = next((f for n, f in sorted(self.ttf_fonts.items()) if n.startswith(name)), None)
        if font is None:
            sys.exit(f"no TrueType font for {name}")
        return self.font(font)

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
        # Normals come as three components a vertex, or four in some meshes.
        normals = np.array(handler.m_Normals, dtype=float).reshape(len(positions), -1)[:, :3]
        uv = np.array(handler.m_UV0, dtype=float) if handler.m_UV0 is not None and len(handler.m_UV0) else np.zeros((len(positions), 2))
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
                "materialValues": material_values(renderer.m_Materials[i], self.textures[tex_file]),
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

    def particle_textures(self, renderer):
        """Each of a particle renderer's materials' textures (the particles', then the trails'), or None."""
        textures = []
        for material in renderer.m_Materials:
            tex_pptr = texture_name(material.deref().read())[0] if material.path_id else None
            textures.append(self.texture(tex_pptr)[0] if tex_pptr else None)
        return textures

    def particle_materials(self, renderer):
        values = []
        for material in renderer.m_Materials:
            tex_pptr = texture_name(material.deref().read())[0] if material.path_id else None
            if not material.path_id:
                values.append(None)
                continue
            values.append(material_values(material, self.textures[self.texture(tex_pptr)[1]] if tex_pptr else None))
        return values

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
# Unity's built-in Cube mesh: 1 x 1 x 1, centered, each face mapping its texture once.
BUILTIN_CUBE = 10202


def cube_part(exporter, renderer, bone):
    """A built-in Cube as a part on `bone`, with its renderer's first material."""
    material_pptr = renderer.m_Materials[0]
    tex_pptr, scale, offset = texture_name(material_pptr.deref().read())
    if tex_pptr is None:
        return None
    texture, tex_file = exporter.texture(tex_pptr)
    positions, normals, uvs, indices = [], [], [], []
    for axis in range(3):
        for sign in (1, -1):
            normal = [0, 0, 0]
            normal[axis] = sign
            u_axis, v_axis = [0, 0, 0], [0, 0, 0]
            u_axis[(axis + 1) % 3] = 1
            v_axis[(axis + 2) % 3] = 1
            base = len(positions) // 3
            for u, v in ((-0.5, -0.5), (0.5, -0.5), (0.5, 0.5), (-0.5, 0.5)):
                positions += [normal[i] * 0.5 + u_axis[i] * u + v_axis[i] * v for i in range(3)]
                normals += normal
                uvs += [(u + 0.5) * scale[0] + offset[0], 1 - ((v + 0.5) * scale[1] + offset[1])]
            # Unity's front faces wind with their cross product along the normal; reversed, as the mod flips z.
            first = [base, base + 1, base + 2, base, base + 2, base + 3] if sign > 0 else [base, base + 2, base + 1, base, base + 3, base + 2]
            indices += [first[i] for i in (0, 2, 1, 3, 5, 4)]
    part = {
        "texture": texture, "textureFile": tex_file, "material": material_pptr.deref().read().m_Name,
        "materialValues": material_values(material_pptr, exporter.textures[tex_file]),
        "positions": positions, "normals": normals, "uvs": uvs, "indices": indices,
        "joints": [bone], "binds": [np.eye(4).flatten().tolist()], "influences": 1,
        "jointIndices": [0] * (len(positions) // 3), "jointWeights": [1.0] * (len(positions) // 3),
    }
    emissive = exporter.emissive(material_pptr)
    if emissive:
        part["emissive"] = emissive
    return part


def export_parts(exporter, rig, glow_colors):
    """The rig's meshes, and the built-in quads (screens, glows) as their bone and texture.

    `glow_colors` tints the glow of the renderers it names (by component path id).
    """
    parts = []
    quads = []
    sprites = []
    particles = []
    # The systems an EnviroGibModifier takes the color of the surface they come off.
    tinted = set()
    for go_id, active in rig.active.items():
        for c in _objects[go_id].read().m_Component:
            obj = c.component.deref()
            if obj.type.name == "MonoBehaviour" and script_name(obj) == "EnviroGibModifier":
                tinted.add(obj.read().particleSystem.path_id)
    for go_id, active in rig.active.items():
        if not active:
            continue
        go = _objects[go_id].read()
        for c in go.m_Component:
            obj = c.component.deref()
            kind = obj.type.name
            if kind == "ParticleSystemRenderer":
                r = obj.read()
                system = next(x.component.deref() for x in go.m_Component if x.component.deref().type.name == "ParticleSystem")
                particles.append({
                    "name": go.m_Name,
                    "bone": rig.index[transform_of(go).object_reader.path_id],
                    "textures": exporter.particle_textures(r),
                    "materials": exporter.particle_materials(r),
                    "renderMode": obj.read_typetree()["m_RenderMode"],
                    "surfaceTinted": system.path_id in tinted,
                    **particle_values(system),
                    **animated_texture(exporter, go),
                })
                continue
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
                    if mesh_filter.m_Mesh.path_id == BUILTIN_CUBE and r.m_Materials and r.m_Materials[0].path_id:
                        part = cube_part(exporter, r, rig.index[transform_of(go).object_reader.path_id])
                        if part is not None:
                            part["name"] = go.m_Name
                            parts.append(part)
                    elif mesh_filter.m_Mesh.path_id == BUILTIN_QUAD and r.m_Materials and r.m_Materials[0].path_id:
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
    return parts, quads, sprites, particles


# A white pixel, for Images that have no sprite.
BLANK = f"{NAMESPACE}:textures/viewmodel/blank.png"

# UnityEngine.UI.Image.Type and FillMethod.
IMAGE_TYPES = {0: "simple", 1: "sliced", 2: "tiled", 3: "filled"}
FILL_METHODS = {0: "horizontal", 1: "vertical", 2: "radial90", 3: "radial180", 4: "radial360"}


# TextMeshPro's alignments, as UI Text's TextAnchor columns and rows.
TMP_COLUMNS = {1: 0, 2: 1, 4: 2, 8: 0, 16: 0, 32: 1}
TMP_ROWS = {256: 0, 512: 1, 1024: 2, 2048: 2, 4096: 1, 8192: 0}


def export_canvases(exporter, rig, shown_by_code):
    """World-space canvases: each Image's rect in its canvas's units, laid out by RectTransform anchors. Images
    that are off are left out, unless a script turns them on (`shown_by_code`); those say they start off.

    Returns the canvases; by Image and Text component path id, where each landed (canvas, index); and by
    transform path id, each laid out rect.
    """
    canvases = []
    placed = {}
    rects = {}
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
        texts = []

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
            rects[transform.object_reader.path_id] = own_rect
            for c in child_go.m_Component:
                obj = c.component.deref()
                if obj.type.name == "MonoBehaviour" and script_name(obj) == "TextMeshProUGUI":
                    tree = obj.read_typetree()
                    if tree["m_Enabled"]:
                        color = tree["m_fontColor"]
                        placed[obj.path_id] = (len(canvases), len(texts))
                        texts.append({
                            "name": child_go.m_Name,
                            "rect": [round(v, 6) for v in own_rect],
                            "pivot": [transform.m_Pivot.x, transform.m_Pivot.y],
                            "scale": transform.m_LocalScale.x,
                            "color": [color["r"], color["g"], color["b"], color["a"]],
                            "font": exporter.tmp_font(obj.read().m_fontAsset),
                            "fontSize": tree["m_fontSize"],
                            "alignment": TMP_ROWS[tree["m_VerticalAlignment"]] * 3 + TMP_COLUMNS[tree["m_HorizontalAlignment"]],
                            "text": tree["m_text"],
                        })
                    continue
                if obj.type.name == "MonoBehaviour" and script_name(obj) == "Text":
                    tree = obj.read_typetree()
                    if tree["m_Enabled"]:
                        color = tree["m_Color"]
                        font = tree["m_FontData"]
                        placed[obj.path_id] = (len(canvases), len(texts))
                        texts.append({
                            "name": child_go.m_Name,
                            "rect": [round(v, 6) for v in own_rect],
                            "pivot": [transform.m_Pivot.x, transform.m_Pivot.y],
                            "scale": transform.m_LocalScale.x,
                            "color": [color["r"], color["g"], color["b"], color["a"]],
                            "font": exporter.font(obj.read().m_FontData.m_Font),
                            "fontSize": font["m_FontSize"],
                            "alignment": font["m_Alignment"],
                            "text": tree["m_Text"],
                        })
                    continue
                if obj.type.name != "MonoBehaviour" or script_name(obj) != "Image":
                    continue
                tree = obj.read_typetree()
                image = obj.read()
                if not tree["m_Enabled"] and obj.path_id not in shown_by_code:
                    continue
                color = tree["m_Color"]
                placed[obj.path_id] = (len(canvases), len(images))
                if image.m_Sprite.path_id:
                    sprite = image.m_Sprite.deref().read()
                    sprite_tree = image.m_Sprite.deref().read_typetree()
                    # Sliced borders are in sprite pixels; the canvas draws them at its reference pixels per unit.
                    units = reference_ppu / sprite_tree["m_PixelsToUnits"] / tree["m_PixelsPerUnitMultiplier"]
                    border = sprite_tree["m_Border"]
                    texture = exporter.texture(sprite.m_RD.texture)[0]
                    border = [border["x"] * units, border["y"] * units, border["z"] * units, border["w"] * units], [
                        border["x"] / sprite_tree["m_Rect"]["width"], border["y"] / sprite_tree["m_Rect"]["height"],
                        border["z"] / sprite_tree["m_Rect"]["width"], border["w"] / sprite_tree["m_Rect"]["height"]]
                else:
                    # Without a sprite an Image is a plain rectangle in its color.
                    texture = BLANK
                    border = [0, 0, 0, 0], [0, 0, 0, 0]
                images.append({
                    "name": child_go.m_Name,
                    "rect": [round(v, 6) for v in own_rect],
                    "texture": texture,
                    "color": [color["r"], color["g"], color["b"], color["a"]],
                    "type": IMAGE_TYPES[tree["m_Type"]] if texture else "simple",
                    "border": border[0],
                    "uvBorder": border[1],
                    "fillMethod": FILL_METHODS[tree["m_FillMethod"]],
                    "fillOrigin": tree["m_FillOrigin"],
                    "fillClockwise": bool(tree["m_FillClockwise"]),
                    "fillAmount": tree["m_FillAmount"],
                })
                if not tree["m_Enabled"]:
                    images[-1]["enabled"] = False
            for child in transform.m_Children:
                t = child.deref().read()
                walk(t, layout(t, own_rect))

        walk(root, rect)
        canvases.append({"bone": rig.index[root.object_reader.path_id], "images": images, "texts": texts})
    return canvases, placed, rects


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


def material_texture(exporter, material_pptr):
    """A material's main texture, as the mod names it."""
    return exporter.texture(texture_name(material_pptr.deref().read())[0])[0]


def chainsaw_values(exporter, rig, shotgun):
    """The Sawed-On's chainsaw: its revving damage zone (a HurtZone on a capsule trigger), and the blade's and
    broken saw's other textures."""
    zone_go = shotgun.sawZone.deref().read().m_GameObject.read()
    zone = shotgun.sawZone.deref().read_typetree()
    capsule = next(c.component.deref().read() for c in zone_go.m_Component if c.component.deref().type.name == "CapsuleCollider")
    return {
        "sawZone": {
            "bone": rig.index[transform_of(zone_go).object_reader.path_id],
            "center": [capsule.m_Center.x, capsule.m_Center.y, capsule.m_Center.z],
            "radius": capsule.m_Radius, "height": capsule.m_Height, "direction": capsule.m_Direction,
            "hurtCooldown": zone["hurtCooldown"], "enemyDamage": zone["enemyDamageOverride"],
        },
        "bladeMotionTexture": material_texture(exporter, shotgun.chainsawBladeMotionMaterial),
        "brokenTexture": material_texture(exporter, shotgun.chainsawBrokenMaterial),
    }


def renderer_material(exporter, renderer):
    """A line or trail renderer's material: its texture (white if it has none), colors and blending."""
    material = renderer.m_Materials[0]
    tex_pptr = texture_name(material.deref().read())[0]
    texture, name = exporter.texture(tex_pptr) if tex_pptr else (f"{NAMESPACE}:textures/viewmodel/blank.png", None)
    values = material_values(material, exporter.textures[name]) if name else {**material_values_untextured(material), "textureAlpha": 1}
    return {"texture": texture, **values}


def material_values_untextured(material_pptr):
    tree = material_pptr.deref().read_typetree()
    props = tree["m_SavedProperties"]
    return {"colors": {k: [v["r"], v["g"], v["b"], v["a"]] for k, v in props["m_Colors"] if k in ("_Color", "_EmissionColor", "_TintColor")},
            "floats": {k: v for k, v in props["m_Floats"] if k in ("_SrcBlend", "_DstBlend", "_Mode")},
            "keywords": tree.get("m_ValidKeywords") or []}


def box_values(go):
    """The object's BoxCollider, in its own space."""
    box = next(c.component.deref().read() for c in go.m_Component if c.component.deref().type.name == "BoxCollider")
    return {"center": [box.m_Center.x, box.m_Center.y, box.m_Center.z], "size": [box.m_Size.x, box.m_Size.y, box.m_Size.z]}


def trail_values(exporter, go):
    """The object's TrailRenderer: how long points last, its width over the trail (times the multiplier), its
    color over the trail and its material."""
    renderer = next(c.component.deref() for c in go.m_Component if c.component.deref().type.name == "TrailRenderer")
    tree = renderer.read_typetree()
    params = tree["m_Parameters"]
    return {"time": tree["m_Time"], "minVertexDistance": tree["m_MinVertexDistance"], "width": params["widthMultiplier"],
            "widthCurve": [[k["time"], k["value"]] for k in params["widthCurve"]["m_Curve"]],
            "color": gradient_value(params["colorGradient"]), "material": renderer_material(exporter, renderer.read())}


def line_values(exporter, go):
    """The LineRenderer under the object: its width and material."""
    def find(transform):
        for c in transform.m_GameObject.read().m_Component:
            if c.component.deref().type.name == "LineRenderer":
                return c.component.deref()
        for child in transform.m_Children:
            found = find(child.deref().read())
            if found is not None:
                return found
        return None
    renderer = find(transform_of(go))
    params = renderer.read_typetree()["m_Parameters"]
    return {"width": params["widthMultiplier"] * params["widthCurve"]["m_Curve"][0]["value"], "material": renderer_material(exporter, renderer.read())}


def beam_lines(exporter, go):
    """A beam's LineRenderers: its own, then the ones under it that copy its points and width (CopyLineRenderer),
    each with its width, its colors along the beam, how its texture runs along it and its material."""
    lines = []

    def add(renderer, components):
        tree = renderer.read_typetree()
        params = tree["m_Parameters"]
        scale = params["textureScale"]
        line = {"width": params["widthMultiplier"] * params["widthCurve"]["m_Curve"][0]["value"],
                "color": gradient_value(params["colorGradient"]), "textureMode": params["textureMode"],
                "textureScale": [scale["x"], scale["y"]], "material": renderer_material(exporter, renderer.read())}
        electricity = next((c for c in components if c.type.name == "MonoBehaviour" and script_name(c) == "ElectricityLine"), None)
        if electricity is not None:
            values = electricity.read_typetree()
            line["electricity"] = {"frames": exporter.texture_array(electricity.read().electricityArray),
                                   "colors": gradient_value(values["colors"]),
                                   **{k: values[k] for k in ("minWidth", "maxWidth", "fadeSpeed")}}
        lines.append(line)

    def walk(transform, root):
        child = transform.m_GameObject.read()
        components = [c.component.deref() for c in child.m_Component]
        renderer = next((c for c in components if c.type.name == "LineRenderer"), None)
        copies = any(c.type.name == "MonoBehaviour" and script_name(c) == "CopyLineRenderer" for c in components)
        if renderer is not None and (root or copies):
            add(renderer, components)
        for c in transform.m_Children:
            walk(c.deref().read(), False)

    walk(transform_of(go), True)
    return lines


def go_name_of(component_pptr):
    """The name of the GameObject a referenced component sits on, or None."""
    return component_pptr.deref().read().m_GameObject.read().m_Name if component_pptr.path_id else None


def color_list(color):
    return [color.r, color.g, color.b, color.a]


def placed_under(transform, placed):
    """Where the Images and Texts in a transform's subtree landed (canvas, index), as two lists."""
    images, texts = [], []

    def walk(t):
        for c in t.m_GameObject.read().m_Component:
            obj = c.component.deref()
            if obj.path_id in placed and obj.type.name == "MonoBehaviour":
                (texts if script_name(obj) in ("Text", "TextMeshProUGUI") else images).append(list(placed[obj.path_id]))
        for child in t.m_Children:
            walk(child.deref().read())

    walk(transform)
    return {"images": images, "texts": texts}


def export_scripts(exporter, rig, colors, placed, rects):
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
            elif kind == "Shotgun":
                variation = obj.read_typetree()["variation"]
                scripts["shotgun"] = {"variation": variation, "variationColor": list(colors[variation])}
                shotgun = obj.read()
                if shotgun.sawZone.path_id:
                    scripts["shotgun"].update(chainsaw_values(exporter, rig, shotgun))
            elif kind == "Railcannon":
                railcannon = obj.read()
                tree = obj.read_typetree()
                scripts["railcannon"] = {
                    "variation": tree["variation"],
                    "variationColor": list(colors[tree["variation"]]),
                    "body": go_name_of(railcannon.body),
                    "pips": [go_name_of(p) for p in railcannon.pips],
                    "fullCharge": rig.index[transform_of(railcannon.fullCharge.deref().read()).object_reader.path_id],
                    "shootPoint": rig.index[railcannon.shootPoint.path_id],
                }
            elif kind == "RailCannonPip":
                tree = obj.read_typetree()
                push = tree["pushAmount"]
                scripts.setdefault("railcannonPips", []).append({
                    "bone": rig.index[transform_of(go).object_reader.path_id],
                    "chargeLevel": tree["chargeLevel"],
                    "pushAmount": [push["x"], push["y"], push["z"]],
                })
            elif kind == "Harpoon":
                tree = obj.read_typetree()
                scripts["harpoon"] = {"damage": tree["damage"], "drillHits": tree["drillHits"], "box": box_values(go),
                                      "trail": trail_values(exporter, go)}
            elif kind == "TimeBomb":
                tree = obj.read_typetree()
                scripts["timeBomb"] = {k: tree[k] for k in ("timer", "beeperSize", "beeperPitch")}
            elif kind == "RevolverBeam" and active:
                tree = obj.read_typetree()
                scripts["beam"] = {**{k: tree[k] for k in ("damage", "hitAmount", "maxHitsPerTarget")}, "lines": beam_lines(exporter, go)}
            elif kind == "Chainsaw":
                tree = obj.read_typetree()
                body = next(c.component.deref().read() for c in go.m_Component if c.component.deref().type.name == "Rigidbody")
                scripts["chainsaw"] = {"damage": tree["damage"], "hitAmount": tree["hitAmount"], "mass": body.m_Mass, "box": box_values(go),
                                       "trail": trail_values(exporter, go), "line": line_values(exporter, go)}
            elif kind == "HookArm":
                hook_arm = obj.read()
                renderer = next(x.component.deref() for x in go.m_Component if x.component.deref().type.name == "LineRenderer")
                params = renderer.read_typetree()["m_Parameters"]
                scripts["hookArm"] = {
                    "line": {**line_values(exporter, go), "textureMode": params["textureMode"], "positions": len(renderer.read_typetree()["m_Positions"])},
                    "hand": rig.index[hook_arm.hand.path_id],
                    "hook": rig.index[hook_arm.hook.path_id],
                    "hookModel": hook_arm.hookModel.deref().read().m_Name,
                }
            elif kind == "Nailgun":
                nailgun = obj.read()
                tree = obj.read_typetree()
                color = lambda c: [c["r"], c["g"], c["b"], c["a"]]  # noqa: E731
                # Nailgun.CorrectVariation: its first two variations swap colors.
                color_index = {0: 1, 1: 0}.get(tree["variation"], tree["variation"])
                scripts["nailgun"] = {
                    "variation": tree["variation"],
                    "variationColor": list(colors[color_index]),
                    "fireRate": tree["fireRate"],
                    "spread": tree["spread"],
                    "shootPoints": [rig.index[transform_of(p.deref().read()).object_reader.path_id] for p in nailgun.shootPoints],
                    "barrelHeats": [go_name_of(r) for r in nailgun.barrelHeats],
                    "heatSinkImages": [list(placed[p.path_id]) if p.path_id in placed else None for p in nailgun.heatSinkImages],
                    "ammoText": list(placed[nailgun.ammoText.path_id]) if nailgun.ammoText.path_id in placed else None,
                    "emptyColor": color(tree["emptyColor"]),
                    "fullColor": color(tree["fullColor"]),
                }
                if nailgun.zapperAttachTransform.path_id:
                    slider_fill = lambda p: list(placed[next(  # noqa: E731
                        c.component.deref().path_id for c in p.deref().read().m_FillRect.deref().read().m_GameObject.read().m_Component
                        if c.component.deref().type.name == "MonoBehaviour" and script_name(c.component.deref()) == "Image")])
                    scripts["nailgun"].update({
                        "zapperAttach": rig.index[nailgun.zapperAttachTransform.path_id],
                        "statusText": list(placed[nailgun.statusText.path_id]),
                        "distanceMeter": slider_fill(nailgun.distanceMeter),
                        "zapMeter": slider_fill(nailgun.zapMeter),
                        "zapMeterParts": placed_under(transform_of(nailgun.zapMeter.deref().read().m_GameObject.read()), placed),
                        "warningX": list(placed[nailgun.warningX.path_id]),
                        "rechargingOverlay": placed_under(transform_of(nailgun.rechargingOverlay.deref().read()), placed),
                        "rechargingMeter": list(placed[nailgun.rechargingMeter.path_id]),
                    })
            elif kind == "Spin" and active:
                tree = obj.read_typetree()
                axis = tree["spinDirection"]
                scripts.setdefault("spins", []).append({"bone": rig.index[transform_of(go).object_reader.path_id],
                                                       "axis": [axis["x"], axis["y"], axis["z"]]})
            elif kind == "Slider" and active:
                slider = obj.read()
                fill = slider.m_FillRect.deref().read()
                image = next((c.component.deref() for c in fill.m_GameObject.read().m_Component
                              if c.component.deref().type.name == "MonoBehaviour" and script_name(c.component.deref()) == "Image"), None)
                if image is not None and image.path_id in placed:
                    # The background its value tints (Nailgun's sliderBg): the nearest Image at or above the slider.
                    background = None
                    transform = transform_of(go)
                    while transform is not None and background is None:
                        owner = transform.m_GameObject.read()
                        background = next((c.component.deref().path_id for c in owner.m_Component
                                           if c.component.deref().type.name == "MonoBehaviour" and script_name(c.component.deref()) == "Image"), None)
                        transform = transform.m_Father.deref().read() if transform.m_Father.path_id else None
                    scripts.setdefault("sliders", []).append({
                        "fill": list(placed[image.path_id]),
                        "background": list(placed[background]) if background in placed else None,
                        "direction": obj.read_typetree()["m_Direction"],
                        "fillExtra": [fill.m_SizeDelta.x, fill.m_SizeDelta.y],
                    })
                    # The handle sits at the value's place across its slide area, keeping its own size.
                    handle = slider.m_HandleRect.deref().read() if slider.m_HandleRect.path_id else None
                    handle_image = handle and next((c.component.deref().path_id for c in handle.m_GameObject.read().m_Component
                                                    if c.component.deref().type.name == "MonoBehaviour" and script_name(c.component.deref()) == "Image"), None)
                    if handle_image in placed and handle.m_Father.path_id in rects:
                        scripts["sliders"][-1]["handle"] = list(placed[handle_image])
                        scripts["sliders"][-1]["handleArea"] = [round(v, 6) for v in rects[handle.m_Father.path_id]]
            elif kind == "CopyColor" and active:
                copy = obj.read()
                own = next(c.component.deref().path_id for c in go.m_Component
                           if c.component.deref().type.name == "MonoBehaviour" and script_name(c.component.deref()) == "Image")
                if own in placed and copy.target.path_id in placed:
                    scripts.setdefault("copyColors", []).append({"image": list(placed[own]), "target": list(placed[copy.target.path_id])})
            elif kind == "Nail" and not obj.read_typetree()["sawblade"]:
                tree = obj.read_typetree()
                body = next(c.component.deref().read() for c in go.m_Component if c.component.deref().type.name == "Rigidbody")
                trigger = next(c.component.deref().read() for c in go.m_Component if c.component.deref().type.name == "SphereCollider")
                capsule = next(c.component.deref().read() for c in go.m_Component if c.component.deref().type.name == "CapsuleCollider")
                scale = transform_of(go).m_LocalScale.x
                scripts["nail"] = {**{k: tree[k] for k in ("damage", "fodderDamageBoost", "heated")},
                                   "mass": body.m_Mass, "triggerRadius": trigger.m_Radius * scale, "radius": capsule.m_Radius * scale,
                                   "trail": trail_values(exporter, go), "zapTexture": material_texture(exporter, obj.read().zapMaterial)}
            elif kind == "Zapper":
                zapper = obj.read()
                tree = obj.read_typetree()
                trigger = next(c.component.deref().read() for c in go.m_Component if c.component.deref().type.name == "SphereCollider")
                body = next(t for t in transform_of(go).m_Children if any(
                    c.component.deref().type.name == "BoxCollider" for c in t.deref().read().m_GameObject.read().m_Component))
                body_go = body.deref().read().m_GameObject.read()
                line = next(c.component.deref() for c in go.m_Component if c.component.deref().type.name == "LineRenderer")
                orb_go = zapper.lightningPulseOrb.deref().read().m_GameObject.read()
                orb_line = next(c.component.deref() for c in orb_go.m_Component if c.component.deref().type.name == "LineRenderer")
                line_params = lambda r: r.read_typetree()["m_Parameters"]  # noqa: E731
                scripts["zapper"] = {
                    **{k: tree[k] for k in ("damage", "maxDistance")},
                    "triggerRadius": trigger.m_Radius,
                    "body": rig.index[body.path_id],
                    "box": box_values(body_go),
                    "openProngs": rig.index[transform_of(zapper.openProngs.deref().read()).object_reader.path_id],
                    "closedProngs": rig.index[transform_of(zapper.closedProngs.deref().read()).object_reader.path_id],
                    "orb": rig.index[zapper.lightningPulseOrb.path_id],
                    "line": {"width": line_params(line)["widthMultiplier"], "color": gradient_value(line_params(line)["colorGradient"]),
                             "material": renderer_material(exporter, line.read())},
                    "orbLine": {"width": line_params(orb_line)["widthMultiplier"],
                                "widthCurve": [[k["time"], k["value"]] for k in line_params(orb_line)["widthCurve"]["m_Curve"]],
                                "color": gradient_value(line_params(orb_line)["colorGradient"]),
                                "material": renderer_material(exporter, orb_line.read())},
                }
            elif kind == "ElectricityLine":
                values = obj.read_typetree()
                renderer = next(c.component.deref() for c in go.m_Component if c.component.deref().type.name == "LineRenderer")
                removal = next((c.component.deref().read_typetree() for c in go.m_Component
                                if c.component.deref().type.name == "MonoBehaviour" and script_name(c.component.deref()) == "RemoveOnTime"), None)
                if removal is None:
                    continue
                scripts["electricityLine"] = {
                    "frames": exporter.texture_array(obj.read().electricityArray),
                    "colors": gradient_value(values["colors"]),
                    **{k: values[k] for k in ("minWidth", "maxWidth", "fadeSpeed")},
                    "lifetime": removal["time"],
                    "material": renderer_material(exporter, renderer.read()),
                }
            elif kind == "Magnet":
                tree = obj.read_typetree()
                collider = next(c.component.deref().read() for c in go.m_Component if c.component.deref().type.name == "SphereCollider")
                scripts["magnet"] = {**{k: tree[k] for k in ("strength", "maxWeight", "health")}, "radius": collider.m_Radius,
                                     "bone": rig.index[transform_of(go).object_reader.path_id]}
            elif kind == "Breakable" and active:
                scripts.setdefault("breakables", []).append({"bone": rig.index[transform_of(go).object_reader.path_id], "box": box_values(go)})
            elif kind == "Nail" and obj.read_typetree()["sawblade"]:
                tree = obj.read_typetree()
                body = next(c.component.deref().read() for c in go.m_Component if c.component.deref().type.name == "Rigidbody")
                scripts["sawblade"] = {**{k: tree[k] for k in ("damage", "hitAmount", "multiHitAmount", "chainsaw")}, "mass": body.m_Mass}
                scripts["sawblade"]["box"] = box_values(go)
                scripts["sawblade"]["trail"] = trail_values(exporter, go)
            elif kind == "PhysicalShockwave":
                tree = obj.read_typetree()
                collider = next(c.component.deref().read() for c in go.m_Component if c.component.deref().type.name == "MeshCollider")
                extent = collider.m_Mesh.deref().read().m_LocalAABB.m_Extent
                scripts["physicalShockwave"] = {**{k: tree[k] for k in ("damage", "speed", "maxSize", "force")},
                                                "colliderExtent": [extent.x, extent.y, extent.z]}
            elif kind == "Coin":
                scripts["coin"] = {"flash": coin_flash(exporter, obj.read().flash.deref().read())}
            elif kind == "Explosion" and active:
                tree = obj.read_typetree()
                collider = next((x.component.deref().read() for x in go.m_Component if x.component.deref().type.name == "SphereCollider"), None)
                scripts.setdefault("explosions", []).append({
                    "bone": rig.index[transform_of(go).object_reader.path_id],
                    "colliderRadius": collider.m_Radius if collider else 0,
                    "chunks": bool(obj.read().explosionChunk.path_id) and not tree["lowQuality"],
                    **{k: tree[k] for k in ("speed", "maxSize", "damage", "enemyDamageMultiplier", "pushForceMultiplier",
                                            "halved", "canHit", "lowQuality", "unblockable", "ignite")},
                })
            elif kind in ("ScaleNFade", "SpriteController", "Screenshaker", "AlwaysLookAtCamera") and active:
                tree = obj.read_typetree()
                keys = {"ScaleNFade": ("scale", "fade", "ft", "scaleSpeed", "fadeSpeed"),
                        "SpriteController": ("randomRotation", "blinking", "fadeSpeed", "shrinkSpeed"),
                        "Screenshaker": ("amount", "oneTime"),
                        "AlwaysLookAtCamera": ()}[kind]
                entry = {"bone": rig.index[transform_of(go).object_reader.path_id], **{k: tree[k] for k in keys}}
                scripts.setdefault(kind[0].lower() + kind[1:], []).append(entry)
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
    """The base layer's state machine, and the one clip each layer above it plays, if it plays just one."""
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
    # Layers above the base that play one clip, overriding the base by a weight the game sets (Nailgun's layer 1).
    layers = []
    for extra in c["m_LayerArray"][1:]:
        extra = extra["data"]
        extra_machine = c["m_StateMachineArray"][extra["m_StateMachineIndex"]]["data"]
        extra_states = extra_machine["m_StateConstantArray"]
        clip = -1
        if (len(extra_states) == 1 and not extra_states[0]["data"]["m_TransitionConstantArray"]
                and not extra_machine["m_AnyStateTransitionConstantArray"] and extra["(int&)m_LayerBlendingMode"] == 0):
            nodes = extra_states[0]["data"]["m_BlendTreeConstantArray"][0]["data"]["m_NodeArray"]
            clip = nodes[0]["data"]["m_ClipID"] if len(nodes) == 1 and nodes[0]["data"]["m_ClipID"] != 0xFFFFFFFF else -1
        layers.append({"clip": clip, "weight": extra["m_DefaultWeight"]})
    return {
        "parameters": parameters,
        "layers": layers,
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
    activate_controlled(rig, root_go)
    activate_scripted(rig, root_go)
    parts, quads, sprites, particles = export_parts(exporter, rig, variation_glow(root_go, colors))
    canvases, placed, rects = export_canvases(exporter, rig, code_shown_images(rig))
    model = {"origin": list(offset or (0, 0, 0)), "bones": rig.bones, "parts": parts, "quads": quads, "sprites": sprites, "particles": particles,
             "canvases": canvases, "scripts": export_scripts(exporter, rig, colors, placed, rects)}
    animator = find_animator(rig)
    if animator is not None and animator.m_Controller.path_id:
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
    roots = find_roots(env) | referenced_roots(env)
    missing = (set(MODELS) | set(REFERENCED) | set(NESTED)) - set(roots)
    if missing:
        sys.exit(f"models not found: {sorted(missing)}")

    for folder in ("viewmodels", "textures/viewmodel", "textures/item", "font"):
        if (OUT / folder).exists():
            shutil.rmtree(OUT / folder)
    (OUT / "viewmodels").mkdir(parents=True)
    (OUT / "textures" / "viewmodel").mkdir(parents=True)
    from PIL import Image
    Image.new("RGBA", (1, 1), (255, 255, 255, 255)).save(OUT / "textures" / "viewmodel" / "blank.png")

    exporter = Exporter({file_name(obj.read().m_Name): obj for obj in env.objects if obj.type.name == "Font"})
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
