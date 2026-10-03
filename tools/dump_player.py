# /// script
# dependencies = ["UnityPy"]
# ///
"""Dumps ULTRAKILL's player prefab and global physics settings to reference/player/.

Every GameObject under each "Player" root is written with its transform and the serialized
fields of all its components, so movement constants can be read from data instead of guessed.

    uv run tools/dump_player.py
"""
import json
import os
import sys
from pathlib import Path

import UnityPy

sys.path.insert(0, str(Path(__file__).parent))
from ultrakill_paths import BUNDLES, DATA, REPO  # noqa: E402

OUT = REPO / "reference" / "player"


def plain(value):
    """Turns UnityPy typetree values into JSON-friendly data."""
    if isinstance(value, dict):
        return {k: plain(v) for k, v in value.items()}
    if isinstance(value, list):
        if len(value) > 64:
            return f"<{len(value)} items>"
        return [plain(v) for v in value]
    if isinstance(value, (bytes, bytearray)):
        return f"<{len(value)} bytes>"
    return value


def script_name(obj):
    try:
        script = obj.read().m_Script.read()
        return script.m_ClassName
    except Exception:
        return None


def dump_component(pptr):
    obj = pptr.deref()
    tree = obj.read_typetree()
    kind = obj.type.name
    if kind == "MonoBehaviour":
        kind = script_name(obj) or "MonoBehaviour"
    for key in ("m_GameObject", "m_Script", "m_Father", "m_Children", "m_PrefabInstance",
                "m_PrefabAsset", "m_CorrespondingSourceObject", "m_ObjectHideFlags"):
        tree.pop(key, None)
    return kind, plain(tree)


def dump_object(go, path, out):
    entry = {"path": path, "active": go.m_IsActive, "layer": go.m_Layer, "tag": go.m_Tag, "components": []}
    transform = None
    for comp in go.m_Component:
        pptr = comp.component
        kind, data = dump_component(pptr)
        if kind in ("Transform", "RectTransform"):
            transform = pptr.deref().read()
            entry["transform"] = {k: data.get(k) for k in ("m_LocalPosition", "m_LocalRotation", "m_LocalScale")}
            continue
        entry["components"].append({"type": kind, "fields": data})
    out.append(entry)
    if transform is not None:
        for child in transform.m_Children:
            child_go = child.deref().read().m_GameObject.read()
            dump_object(child_go, f"{path}/{child_go.m_Name}", out)


def physics_settings():
    env = UnityPy.load(str(DATA / "globalgamemanagers"))
    wanted = {"PhysicsManager", "TimeManager", "TagManager"}
    result = {}
    for obj in env.objects:
        if obj.type.name in wanted:
            result[obj.type.name] = plain(obj.read_typetree())
    return result


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "settings.json").write_text(json.dumps(physics_settings(), indent=1))

    env = UnityPy.load(str(BUNDLES / "gameprefabs_assets_all.bundle"))
    roots = []
    for obj in env.objects:
        if obj.type.name != "GameObject":
            continue
        go = obj.read()
        if go.m_Name != "Player":
            continue
        transform = next(c.component for c in go.m_Component if c.component.deref().type.name == "Transform")
        if transform.deref().read().m_Father.path_id == 0:
            roots.append(go)

    print(f"{len(roots)} Player roots")
    for i, go in enumerate(roots):
        objects = []
        dump_object(go, "Player", objects)
        target = OUT / f"player_{i}.json"
        target.write_text(json.dumps(objects, indent=1))
        print(f"  {target.relative_to(REPO)}: {len(objects)} objects")


if __name__ == "__main__":
    main()
