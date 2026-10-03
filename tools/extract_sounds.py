# /// script
# dependencies = ["UnityPy", "soundfile"]
# ///
"""Extracts ULTRAKILL's movement and combat sounds into the mod's generated resources (mod/src/generated/resources).

    uv run tools/extract_sounds.py

Writes assets/ultracraft/sounds/*.ogg and assets/ultracraft/sounds.json. Volumes and pitches that ULTRAKILL
sets on a sound's AudioSource are written into sounds.json; the ones its code sets are applied by the mod.
"""
import io
import json
import shutil
import sys
from pathlib import Path

import soundfile
import UnityPy

sys.path.insert(0, str(Path(__file__).parent))
from ultrakill_paths import BUNDLES, REPO  # noqa: E402

OUT = REPO / "mod" / "src" / "generated" / "resources" / "assets" / "ultracraft"
NAMESPACE = "ultracraft"

# ULTRAKILL's SurfaceType enum.
SURFACES = {
    0: "generic", 1: "carpet", 2: "flesh", 3: "glass", 4: "grass", 5: "gravel", 6: "hologram", 7: "marble",
    8: "metal", 9: "metal_grill", 10: "metal_thin", 11: "rubble", 12: "sand", 13: "wet", 14: "wood",
    15: "wood_thin", 16: "metal_high", 17: "metal_low", 18: "dirt", 19: "crunchy", 20: "snow",
}


def file_name(clip_name):
    return "".join(c if c.isalnum() else "_" for c in clip_name.lower()).strip("_")


class Extractor:
    def __init__(self):
        self.files = {}
        self.events = {}

    def clip(self, pptr):
        """Exports an AudioClip once and returns its sound name."""
        clip = pptr.deref().read()
        name = file_name(clip.m_Name)
        if name not in self.files:
            data, rate = soundfile.read(io.BytesIO(next(iter(clip.samples.values()))))
            ogg = OUT / "sounds" / f"{name}.ogg"
            soundfile.write(ogg, data, rate, format="OGG", subtype="VORBIS")
            self.files[name] = ogg
        return f"{NAMESPACE}:{name}"

    def event(self, event, sounds):
        self.events[event] = {"sounds": sounds}

    def add_clip(self, event, pptr, volume=1.0, pitch=1.0):
        self.event(event, [sound(self.clip(pptr), volume, pitch)])

    def add_source(self, event, source):
        self.add_clip(event, source.m_audioClip, source.m_Volume, source.m_Pitch)


def sound(name, volume=1.0, pitch=1.0):
    entry = {"name": name}
    if abs(volume - 1) > 1e-4:
        entry["volume"] = round(volume, 4)
    if abs(pitch - 1) > 1e-4:
        entry["pitch"] = round(pitch, 4)
    return entry


def components(go, kind):
    for c in go.m_Component:
        obj = c.component.deref()
        if obj.type.name == kind:
            yield obj.read()


def children(go):
    for transform in components(go, "Transform"):
        for child in transform.m_Children:
            yield child.deref().read().m_GameObject.read()


def audio_sources(go):
    """AudioSources in a prefab hierarchy, depth first."""
    yield from components(go, "AudioSource")
    for child in children(go):
        yield from audio_sources(child)


def child(go, name):
    return next(c for c in children(go) if c.m_Name == name)


def find_script(env, *fields):
    for obj in env.objects:
        if obj.type.name != "MonoBehaviour":
            continue
        try:
            tree = obj.read_typetree()
        except Exception:
            continue
        if all(f in tree for f in fields):
            return obj.read()
    raise LookupError(f"no MonoBehaviour with {fields}")


def backing(obj, name):
    return getattr(obj, f"<{name}>k__BackingField")


def find_class(env, class_name, go_name, **fields):
    """The `class_name` script on the GameObject called `go_name` whose serialized fields match `fields`."""
    for obj in env.objects:
        if obj.type.name != "MonoBehaviour":
            continue
        try:
            if obj.read().m_Script.read().m_ClassName != class_name:
                continue
            script = obj.read()
        except Exception:
            continue
        if script.m_GameObject.read().m_Name != go_name:
            continue
        tree = obj.read_typetree()
        if all(tree.get(k) == v for k, v in fields.items()):
            return script
    raise LookupError(f"no {class_name} on {go_name}")


def named_source(go, name):
    """The AudioSource on the object called `name` in a prefab hierarchy."""
    def walk(g):
        if g.m_Name == name:
            yield from components(g, "AudioSource")
        for c in children(g):
            yield from walk(c)
    return next(walk(go))


def source_ref(pptr):
    """An AudioSource referenced directly or through its GameObject."""
    obj = pptr.deref()
    if obj.type.name == "AudioSource":
        return obj.read()
    return next(audio_sources(obj.read()))


def combat(env, x):
    piercer = find_class(env, "Revolver", "Revolver Pierce", altVersion=0)
    marksman = find_class(env, "Revolver", "Revolver Ricochet", altVersion=0)
    piercer_go, marksman_go = piercer.m_GameObject.read(), marksman.m_GameObject.read()
    x.add_clip("revolver_shoot.piercer", piercer.gunShots[0])
    x.add_clip("revolver_shoot.marksman", marksman.gunShots[0])
    x.add_source("revolver_super", source_ref(piercer.superGunSound))
    x.add_clip("revolver_charge", named_source(piercer_go, "ChargeEffect").m_audioClip)
    x.add_clip("revolver_charged", piercer.chargedSound)
    x.add_clip("revolver_recharging", piercer.chargingSound)
    x.add_source("revolver_click", named_source(piercer_go, "Cylinder_Bone"))
    x.add_source("coin_ready", named_source(marksman_go, "Canvas"))

    coin_go = marksman.coin.deref().read()
    coin = next(s for s in components(coin_go, "MonoBehaviour") if hasattr(s, "coinHitSound"))
    x.add_source("coin_throw", next(components(coin_go, "AudioSource")))
    x.add_source("coin_spin", named_source(coin_go, "Audio Source"))
    hit = coin.coinHitSound.deref().read()
    x.add_source("coin_hit", named_source(hit, hit.m_Name))
    x.add_source("coin_hit_flip", named_source(hit, "Audio Source"))
    x.add_source("coin_flash", source_ref(coin.flash))
    x.add_source("coin_break", source_ref(coin.coinBreak))

    beam = next(s for s in components(piercer.revolverBeam.deref().read(), "MonoBehaviour") if hasattr(s, "enemyHitSound"))
    x.add_source("beam_hit", source_ref(beam.enemyHitSound))
    x.add_source("beam_ricochet", source_ref(beam.ricochetSound))

    for event, go_name in [("gore.head", "BS Head"), ("gore.limb", "BS Limb"), ("gore.body", "BS Body"), ("gore.small", "BS Body Small")]:
        x.add_source(event, source_ref(find_class(env, "Bloodsplatter", go_name).m_GameObject))

    punch = find_class(env, "Punch", "Arm Blue")
    x.add_source("punch_swoosh", next(components(punch.m_GameObject.read(), "AudioSource")))
    x.add_source("punch_hit", source_ref(punch.normalHit))
    x.add_source("parry_hit", source_ref(punch.specialHit))

    movement = find_script(env, "walkSpeed", "jumpSound")
    x.add_source("hurt", source_ref(movement.hurtScreen.deref().read().m_GameObject))
    x.add_source("heal", source_ref(movement.hpFlash.deref().read().m_GameObject))
    time_controller = find_script(env, "parryLight", "parryFlash")
    parry = time_controller.parryLight.deref().read()
    x.add_source("parry", named_source(parry, "Audio Source"))
    x.add_source("parry_ricochet", named_source(parry, parry.m_Name))


def main():
    assets = BUNDLES / "assets_assets_assets"
    env = UnityPy.load(
        str(BUNDLES / "gameprefabs_assets_all.bundle"),
        str(BUNDLES / "other_assets_all.bundle"),
        str(BUNDLES / "monoscript_monoscripts.bundle"),
        str(assets / "sounds.bundle"),
    )
    if (OUT / "sounds").exists():
        shutil.rmtree(OUT / "sounds")
    (OUT / "sounds").mkdir(parents=True)

    x = Extractor()

    movement = find_script(env, "walkSpeed", "jumpSound")
    x.add_clip("jump", movement.jumpSound)
    x.add_clip("landing", movement.landingSound)
    x.add_clip("wall_jump_final", movement.finalWallJump)
    x.add_clip("dodge", movement.dodgeSound)
    for event, field in [("stamina_fail", "staminaFailSound"), ("slide_stop", "slideStopSound"),
                         ("dash_jump", "dashJumpSound"), ("landing_heavy", "impactDust")]:
        source = next(s for s in audio_sources(getattr(movement, field).deref().read()) if s.m_PlayOnAwake and s.m_audioClip.path_id)
        x.add_source(event, source)

    player = movement.m_GameObject.read()
    x.add_clip("woosh", next(components(child(player, "WallCheck"), "AudioSource")).m_audioClip)

    footsteps = find_script(env, "slideParticles", "wallScrapeParticles")
    for kind, field in [("slide", "slideParticles"), ("wall_scrape", "wallScrapeParticles")]:
        for entry in getattr(footsteps, field):
            surface = SURFACES[backing(entry, "SurfaceType")]
            for source in audio_sources(backing(entry, "particle").deref().read()):
                layer = "base" if file_name(source.m_audioClip.deref().read().m_Name) == "wallcling" else "surface"
                x.add_source(f"{kind}_{layer}.{surface}", source)
    for entry in footsteps.footsteps:
        surface = SURFACES[backing(entry, "SurfaceType")]
        x.event(f"footstep.{surface}", [sound(x.clip(c)) for c in backing(entry, "Clips")])

    combat(env, x)

    (OUT / "sounds.json").write_text(json.dumps(x.events, indent=1, sort_keys=True))
    print(f"{len(x.files)} sounds, {len(x.events)} events -> {OUT.relative_to(REPO)}")


if __name__ == "__main__":
    main()
