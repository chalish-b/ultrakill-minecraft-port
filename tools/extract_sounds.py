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
from ultrakill_surfaces import SURFACES  # noqa: E402

OUT = REPO / "mod" / "src" / "generated" / "resources" / "assets" / "ultracraft"
NAMESPACE = "ultracraft"
# PlayerShape.BLOCKS_PER_UNIT: the player is 3.5 units tall in ULTRAKILL and 1.8 blocks in Minecraft.
BLOCKS_PER_UNIT = 1.8 / 3.5


def file_name(clip_name):
    return "".join(c if c.isalnum() else "_" for c in clip_name.lower()).strip("_")


class Extractor:
    def __init__(self):
        self.files = {}
        self.events = {}

    def clip(self, pptr, mono=False, speed=1.0):
        """Exports an AudioClip once and returns its sound name. Minecraft only places and fades mono sounds, so
        a sound that comes from somewhere in the world is mixed down to one channel. `speed` plays it that much
        faster and higher, for pitches above Minecraft's highest (2)."""
        clip = pptr.deref().read()
        name = file_name(clip.m_Name) + ("_mono" if mono else "") + (f"_x{file_name(str(speed))}" if speed != 1 else "")
        if name not in self.files:
            data, rate = soundfile.read(io.BytesIO(next(iter(clip.samples.values()))))
            if mono and data.ndim > 1:
                data = data.mean(axis=1)
            ogg = OUT / "sounds" / f"{name}.ogg"
            soundfile.write(ogg, data, round(rate * speed), format="OGG", subtype="VORBIS")
            self.files[name] = ogg
        return f"{NAMESPACE}:{name}"

    def event(self, event, sounds):
        self.events[event] = {"sounds": sounds}

    def add_clip(self, event, pptr, volume=1.0, pitch=1.0, mono=False):
        self.event(event, [sound(self.clip(pptr, mono), volume, pitch)])

    def add_source(self, event, source):
        self.add_clip(event, source.m_audioClip, source.m_Volume, source.m_Pitch)

    def add_source_baked(self, event, source):
        """Like add_source, with a pitch above Minecraft's highest baked into the sound."""
        if source.m_Pitch > 2:
            self.event(event, [sound(self.clip(source.m_audioClip, speed=source.m_Pitch), source.m_Volume)])
        else:
            self.add_source(event, source)


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


def audio_sources_tree(go):
    """The serialized fields of the AudioSources in a prefab hierarchy, depth first."""
    for c in go.m_Component:
        obj = c.component.deref()
        if obj.type.name == "AudioSource":
            yield obj.read_typetree()
    for child_go in children(go):
        yield from audio_sources_tree(child_go)


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


def script_class(obj):
    try:
        return obj.read().m_Script.read().m_ClassName
    except Exception:
        return None


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

    sharpshooter = find_class(env, "Revolver", "Revolver Twirl", altVersion=0)
    sharpshooter_go = sharpshooter.m_GameObject.read()
    x.add_clip("revolver_shoot.sharpshooter", sharpshooter.gunShots[0])
    if sharpshooter.superGunSound.path_id:
        x.add_source("revolver_super.sharpshooter", source_ref(sharpshooter.superGunSound))
    x.add_source("revolver_twirl_shot", source_ref(sharpshooter.twirlShotSound))
    x.add_clip("revolver_twirl", named_source(sharpshooter_go, "ChargeEffect").m_audioClip)
    x.add_source("sharpshooter_ready", named_source(sharpshooter_go, "Canvas"))
    sharp_beam = next(s for s in components(sharpshooter.revolverBeamSuper.deref().read(), "MonoBehaviour") if hasattr(s, "ricochetSound"))
    x.add_source("beam_ricochet.sharpshooter", source_ref(sharp_beam.ricochetSound))

    coin_go = marksman.coin.deref().read()
    coin = next(s for s in components(coin_go, "MonoBehaviour") if hasattr(s, "coinHitSound"))
    x.add_source("coin_throw", next(components(coin_go, "AudioSource")))
    x.add_source("coin_spin", named_source(coin_go, "Audio Source"))
    hit = coin.coinHitSound.deref().read()
    x.add_source("coin_hit", named_source(hit, hit.m_Name))
    x.add_source("coin_hit_flip", named_source(hit, "Audio Source"))
    # The flash's RandomPitch and the charge's SoundPitchDip set their pitch; the mod does too.
    flash = source_ref(coin.flash)
    x.add_clip("coin_flash", flash.m_audioClip, flash.m_Volume)
    charge = source_ref(coin.chargeEffect)
    x.add_clip("coin_charge", charge.m_audioClip, charge.m_Volume)
    x.add_source("coin_break", source_ref(coin.coinBreak))

    beam = next(s for s in components(piercer.revolverBeam.deref().read(), "MonoBehaviour") if hasattr(s, "enemyHitSound"))
    x.add_source("beam_hit", source_ref(beam.enemyHitSound))
    x.add_source("beam_ricochet", source_ref(beam.ricochetSound))

    for event, go_name in [("gore.head", "BS Head"), ("gore.limb", "BS Limb"), ("gore.body", "BS Body"), ("gore.small", "BS Body Small")]:
        x.add_source(event, source_ref(find_class(env, "Bloodsplatter", go_name).m_GameObject))

    for arm, go_name in [("feedbacker", "Arm Blue"), ("knuckleblaster", "Arm Red")]:
        punch = find_class(env, "Punch", go_name)
        x.add_source(f"punch_swoosh.{arm}", next(components(punch.m_GameObject.read(), "AudioSource")))
        x.add_source(f"punch_hit.{arm}", source_ref(punch.heavyHit))
    x.add_source("parry_hit", source_ref(punch.specialHit))
    blast = punch.blastWave.deref().read()
    x.add_source("explosion_wave", next(components(blast, "AudioSource")))
    x.add_source("fist_reload", next(components(punch.shellEjector.deref().read().m_GameObject.read(), "AudioSource")))
    x.add_source("shell_impact", next(components(punch.shell.deref().read(), "AudioSource")))

    shotgun = find_class(env, "Shotgun", "Shotgun Grenade", variation=0)
    x.add_clip("shotgun_shoot", shotgun.shootSound)
    x.add_clip("shotgun_click", shotgun.clickSound)
    x.add_clip("shotgun_click_charge", shotgun.clickChargeSound)
    x.add_source("shotgun_heat_release", next(components(shotgun.heatSinkSMR.deref().read().m_GameObject.read(), "AudioSource")))
    pellet = next(s for s in components(shotgun.bullet.deref().read(), "MonoBehaviour") if hasattr(s, "playerBullet"))
    x.add_source("bullet_spark", next(components(pellet.explosionEffect.deref().read(), "AudioSource")))
    x.add_source("explosion", next(components(punch.parriedProjectileHitObject.deref().read(), "AudioSource")))
    x.add_clip("shotgun_smack", shotgun.smackSound)
    charge = next(components(shotgun.chargeSoundBubble.deref().read(), "AudioSource"))
    x.add_clip("shotgun_charge", charge.m_audioClip, charge.m_Volume)
    x.add_clip("shotgun_core_throw", next(components(shotgun.grenadeSoundBubble.deref().read(), "AudioSource")).m_audioClip)
    core = next(s for s in components(shotgun.grenade.deref().read(), "MonoBehaviour") if hasattr(s, "superExplosion"))
    x.add_source("explosion_super", next(components(core.superExplosion.deref().read(), "AudioSource")))
    saw = find_class(env, "Shotgun", "Shotgun Saw", variation=2)
    x.add_source("chainsaw_idle", next(components(saw.chainsawRenderer.deref().read().m_GameObject.read(), "AudioSource")))
    rev = next(components(saw.chargeSoundBubble.deref().read(), "AudioSource"))
    x.add_clip("chainsaw_rev", rev.m_audioClip, rev.m_Volume)
    x.add_source("chainsaw_grind", source_ref(saw.environmentalSawSound))
    # The break and hit effects' RandomPitch set their pitch; the mod does too. Both also throw sparks, with a
    # ricochet sound of their own.
    breaking = saw.chainsawBreakEffect.deref().read()
    source = next(components(breaking, "AudioSource"))
    x.add_clip("chainsaw_break", source.m_audioClip, source.m_Volume)
    hit = find_class(env, "HurtZone", "SawTrigger").hurtParticle.deref().read()
    source = next(components(hit, "AudioSource"))
    x.add_clip("chainsaw_hit", source.m_audioClip, source.m_Volume)
    source = named_source(hit, "SparksSawblade")
    x.add_clip("chainsaw_spark", source.m_audioClip, source.m_Volume)
    thrown = saw.chainsaw.deref().read()
    # The thrown chainsaw's hum comes from where it is, fading out over its AudioSource's range (in blocks).
    spin = thrown.stoppedAud.deref()
    x.add_clip("chainsaw_spin", spin.read().m_audioClip, mono=True)
    x.events["chainsaw_spin"]["sounds"][0]["attenuation_distance"] = round(spin.read_typetree()["MaxDistance"] * BLOCKS_PER_UNIT)
    x.add_source("chainsaw_snap", thrown.ropeSnapSound.deref().read())
    blade = next(s for s in components(thrown.sawbladeVersion.deref().read().m_GameObject.read(), "MonoBehaviour") if hasattr(s, "sawblade"))
    pump = find_class(env, "Shotgun", "Shotgun Pump", variation=1)
    x.add_clip("shotgun_pump1", pump.pump1sound)
    x.add_clip("shotgun_pump2", pump.pump2sound)
    x.add_source("shotgun_pump_charge", next(components(pump.pumpChargeSound.deref().read(), "AudioSource")))
    x.add_source("shotgun_warning_beep", next(components(pump.warningBeep.deref().read(), "AudioSource")))

    # The Whiplash's one-shots set their pitch with RandomPitch, and the mod does too. The hit and the clinks come
    # from where they happen; the loops play on the arm's own AudioSource, at its volume.
    hook = find_class(env, "HookArm", "Hook Arm")
    for event, field in [("whiplash_throw", "throwSound"), ("whiplash_pull", "pullSound"), ("whiplash_catch", "catchSound"),
                         ("whiplash_pull_done", "pullDoneSound")]:
        source = source_ref(getattr(hook, field))
        x.add_clip(event, source.m_audioClip, source.m_Volume)
    stop = named_source(hook.pullDoneSound.deref().read(), "Audio Source")
    x.add_clip("whiplash_pull_done_stop", stop.m_audioClip, stop.m_Volume)
    x.add_source("whiplash_woosh", source_ref(hook.wooshSound))
    for event, field in [("whiplash_hit", "hitSound"), ("whiplash_clink", "clinkSparks"), ("whiplash_clink_object", "clinkObjectSparks")]:
        pptr = getattr(hook, field)
        source = source_ref(pptr)
        x.add_clip(event, source.m_audioClip, source.m_Volume, mono=True)
        x.events[event]["sounds"][0]["attenuation_distance"] = round(next(audio_sources_tree(pptr.deref().read())).get("MaxDistance") * BLOCKS_PER_UNIT)
    arm_volume = next(components(hook.m_GameObject.read(), "AudioSource")).m_Volume
    x.add_clip("whiplash_throw_loop", hook.throwLoop, arm_volume)
    x.add_clip("whiplash_pull_loop", hook.pullLoop, arm_volume)

    railcannon(env, x)
    nailgun(env, x)
    jumpstart(env, x)
    rocket_launcher(env, x)

    ground = next(g for g in (o.read() for o in env.objects if o.type.name == "MonoBehaviour" and script_class(o) == "GroundCheck") if g.shockwave.path_id)
    shockwave = next(m for m in components(ground.shockwave.deref().read(), "MonoBehaviour") if hasattr(m, "soundEffect"))
    x.add_source("slam_shockwave", next(components(shockwave.soundEffect.deref().read(), "AudioSource")))

    movement = find_script(env, "walkSpeed", "jumpSound")
    x.add_source("hurt", source_ref(movement.hurtScreen.deref().read().m_GameObject))
    x.add_source("heal", source_ref(movement.hpFlash.deref().read().m_GameObject))
    time_controller = find_script(env, "parryLight", "parryFlash")
    parry = time_controller.parryLight.deref().read()
    x.add_source("parry", named_source(parry, "Audio Source"))
    x.add_source("parry_ricochet", named_source(parry, parry.m_Name))


def railcannon(env, x):
    """Each railcannon's shot (its fireSound's sources, in order), the hum while it's fully charged (Malicious's
    pitch is set by its code), and each charge pip's hum and click, by charge level (RailCannonPip); the
    Malicious beam's explosion; and the full charge sound (WeaponCharges)."""
    for variant, go_name in [("electric", "Railcannon Electric"), ("screwdriver", "Railcannon Harpoon"), ("malicious", "Railcannon Malicious")]:
        gun = find_class(env, "Railcannon", go_name)
        for i, source in enumerate(audio_sources(gun.fireSound.deref().read())):
            x.add_source_baked(f"railcannon_fire.{variant}.{i}", source)
        hum = next(components(gun.fullCharge.deref().read(), "AudioSource"))
        if variant == "malicious":
            x.add_clip(f"railcannon_full.{variant}", hum.m_audioClip, hum.m_Volume)
        else:
            x.add_source(f"railcannon_full.{variant}", hum)

        def pips(go):
            for c in go.m_Component:
                obj = c.component.deref()
                if obj.type.name == "MonoBehaviour" and script_class(obj) == "RailCannonPip":
                    level = int(obj.read_typetree()["chargeLevel"])
                    for source in audio_sources(go):
                        kind = "idle" if source.Loop else "click"
                        x.add_source_baked(f"railcannon_pip_{kind}.{variant}.{level}", source)
            for child_go in children(go):
                pips(child_go)
        pips(gun.m_GameObject.read())
    # The Malicious railcannon's explosion: its RandomPitch plays it at half pitch, a fifth of that either way;
    # the half is baked in, below Minecraft's lowest (0.5).
    malicious = find_class(env, "Railcannon", "Railcannon Malicious")
    beam = next(s for s in components(malicious.beam.deref().read(), "MonoBehaviour") if hasattr(s, "hitParticle"))
    explosion = next(components(beam.hitParticle.deref().read(), "AudioSource"))
    x.event("explosion_malicious_railcannon", [sound(x.clip(explosion.m_audioClip, speed=0.5), explosion.m_Volume)])
    # The Screwdriver's drill: its hits on a wall and an enemy (their pitch is set by its code), its drilling loop,
    # its TimeBomb's beep and the debris sound when it breaks. They come from where the drill is.
    screwdriver = find_class(env, "Railcannon", "Railcannon Harpoon")
    drill_go = screwdriver.beam.deref().read()
    drill = next(s for s in components(drill_go, "MonoBehaviour") if hasattr(s, "drillHits"))
    for event, pptr in [("drill_hit_wall", drill.environmentHitSound), ("drill_hit_enemy", drill.enemyHitSound)]:
        x.add_clip(event, pptr, 0.4, mono=True)
        x.events[event]["sounds"][0]["attenuation_distance"] = round(100 * BLOCKS_PER_UNIT)
    loop = source_ref(drill.drillSound)
    loop_go = loop.m_GameObject.read()
    x.add_clip("drill_loop", loop.m_audioClip, loop.m_Volume, mono=True)
    x.events["drill_loop"]["sounds"][0]["attenuation_distance"] = round(next(audio_sources_tree(loop_go))["MaxDistance"] * BLOCKS_PER_UNIT)
    bomb = next(s for s in components(drill_go, "MonoBehaviour") if hasattr(s, "beepLight"))
    beep_go = bomb.beepLight.deref().read()
    beep = next(components(beep_go, "AudioSource"))
    x.add_clip("drill_beep", beep.m_audioClip, beep.m_Volume, mono=True)
    x.events["drill_beep"]["sounds"][0]["attenuation_distance"] = round(next(audio_sources_tree(beep_go))["MaxDistance"] * BLOCKS_PER_UNIT)
    break_go = bomb.explosion.deref().read()
    debris = next(components(break_go, "AudioSource"))
    x.add_clip("drill_break", debris.m_audioClip, debris.m_Volume, mono=True)
    x.events["drill_break"]["sounds"][0]["attenuation_distance"] = round(next(audio_sources_tree(break_go))["MaxDistance"] * BLOCKS_PER_UNIT)
    pitch = next(s for s in components(break_go, "MonoBehaviour") if hasattr(s, "pitchVariation"))
    print("drill_break pitch", debris.m_Pitch, "RandomPitch", pitch.defaultPitch, pitch.pitchVariation, "beep max", next(audio_sources_tree(beep_go))["MaxDistance"])
    charges = next(o.read() for o in env.objects if o.type.name == "MonoBehaviour" and script_class(o) == "WeaponCharges"
                   and o.read().railCannonFullChargeSound.path_id)
    x.add_source("railcannon_full_charge", next(components(charges.railCannonFullChargeSound.deref().read(), "AudioSource")))


def nailgun(env, x):
    """The nailguns' shot (their muzzle flash's; its pitch and volume are set by the code), the barrels' whir, the
    Overheat's steam, the meter's chime as a heat sink or magnet charge fills, the Attractor's empty clicks; a nail's
    hits on a wall and an enemy, from where it hit; the magnet's throw, hits, beep and break."""
    overheat = find_class(env, "Nailgun", "Nailgun Overheat", altVersion=0)
    attractor = find_class(env, "Nailgun", "Nailgun Magnet", altVersion=0)
    overheat_go = overheat.m_GameObject.read()
    shot = next(components(overheat.muzzleFlash.deref().read(), "AudioSource"))
    x.add_clip("nailgun_shoot", shot.m_audioClip)
    whir = named_source(overheat_go, "Barrel_L")
    x.add_clip("nailgun_spin", whir.m_audioClip, whir.m_Volume)
    x.add_source("nailgun_steam", named_source(overheat_go, "Particle System"))
    chime = next(components(overheat_go, "AudioSource"))
    x.add_clip("nailgun_charge", chime.m_audioClip, chime.m_Volume)
    # The no-ammo click's RandomPitch replaces its AudioSource's pitch.
    no_ammo = source_ref(attractor.noAmmoSound)
    x.add_clip("nailgun_no_ammo", no_ammo.m_audioClip, no_ammo.m_Volume)
    x.add_source("nailgun_last_shot", source_ref(attractor.lastShotSound))
    nail_go = overheat.nail.deref().read()
    nail = next(s for s in components(nail_go, "MonoBehaviour") if hasattr(s, "zapMaterial"))
    reach = round(next(audio_sources_tree(nail_go))["MaxDistance"] * BLOCKS_PER_UNIT)
    for event, pptr in [("nail_hit_wall", nail.environmentHitSound), ("nail_hit_enemy", nail.enemyHitSound)]:
        x.add_clip(event, pptr, mono=True)
        x.events[event]["sounds"][0]["attenuation_distance"] = reach
    shoot = source_ref(attractor.magnetShotSound)
    x.add_clip("magnet_shoot", shoot.m_audioClip, shoot.m_Volume)
    magnet_go = attractor.magnetNail.deref().read()
    harpoon = next(s for s in components(magnet_go, "MonoBehaviour") if hasattr(s, "drillHits"))
    reach = round(next(audio_sources_tree(magnet_go))["MaxDistance"] * BLOCKS_PER_UNIT)
    for event, pptr in [("magnet_hit_wall", harpoon.environmentHitSound), ("magnet_hit_enemy", harpoon.enemyHitSound)]:
        x.add_clip(event, pptr, 0.4, mono=True)
        x.events[event]["sounds"][0]["attenuation_distance"] = reach
    bomb = next(s for s in components(magnet_go, "MonoBehaviour") if hasattr(s, "beepLight"))
    beep_go = bomb.beepLight.deref().read()
    beep = next(components(beep_go, "AudioSource"))
    x.add_clip("magnet_beep", beep.m_audioClip, beep.m_Volume, mono=True)
    x.events["magnet_beep"]["sounds"][0]["attenuation_distance"] = round(next(audio_sources_tree(beep_go))["MaxDistance"] * BLOCKS_PER_UNIT)
    break_go = bomb.explosion.deref().read()
    debris = next(components(break_go, "AudioSource"))
    x.add_clip("magnet_break", debris.m_audioClip, debris.m_Volume, mono=True)
    x.events["magnet_break"]["sounds"][0]["attenuation_distance"] = round(next(audio_sources_tree(break_go))["MaxDistance"] * BLOCKS_PER_UNIT)
    pitch = next(s for s in components(break_go, "MonoBehaviour") if hasattr(s, "pitchVariation"))
    print("magnet_break pitch", debris.m_Pitch, "RandomPitch", pitch.defaultPitch, pitch.pitchVariation)


def rocket_launcher(env, x):
    """The rocket launcher's shot and its clunks (their pitch is set by the code); the Freezeframe's clock starting
    and stopping, its ticking while rockets are frozen and its windup as the freeze recharges (their pitch is set by
    the code); the rocket's hum in flight and its level-up, from where the rocket is (the hum's pitch is set by the
    code); the harmless explosion where a rocket hits a wall; and the sound of getting on a rocket."""
    launcher = find_class(env, "RocketLauncher", "Rocket Launcher Freeze", variation=0)
    shot = next(components(launcher.m_GameObject.read(), "AudioSource"))
    x.add_clip("rocket_launcher_shoot", shot.m_audioClip, shot.m_Volume)
    clunk = source_ref(launcher.clunkSound)
    x.add_clip("rocket_launcher_clunk", clunk.m_audioClip, clunk.m_Volume)
    x.add_source("rocket_freeze", source_ref(launcher.timerFreezeSound))
    x.add_source("rocket_unfreeze", source_ref(launcher.timerUnfreezeSound))
    x.add_source("rocket_freeze_tick", source_ref(launcher.timerTickSound))
    windup = source_ref(launcher.timerWindupSound)
    x.add_clip("rocket_freeze_windup", windup.m_audioClip, windup.m_Volume)
    rocket_go = launcher.rocket.deref().read()
    rocket = next(s for s in components(rocket_go, "MonoBehaviour") if hasattr(s, "rocketSpeed"))
    hum = next(components(rocket_go, "AudioSource"))
    x.add_clip("rocket_fly", hum.m_audioClip, hum.m_Volume, mono=True)
    x.events["rocket_fly"]["sounds"][0]["attenuation_distance"] = round(next(audio_sources_tree(rocket_go))["MaxDistance"] * BLOCKS_PER_UNIT)
    level_up_go = rocket.levelUpEffect.deref().read()
    level_up = next(audio_sources(level_up_go))
    x.event("rocket_level_up", [sound(x.clip(level_up.m_audioClip, mono=True, speed=level_up.m_Pitch), level_up.m_Volume)])
    x.events["rocket_level_up"]["sounds"][0]["attenuation_distance"] = round(next(audio_sources_tree(level_up_go))["MaxDistance"] * BLOCKS_PER_UNIT)
    x.add_source("explosion_rocket_harmless", next(audio_sources(rocket.harmlessExplosion.deref().read())))
    ride = next(audio_sources(rocket.playerRideSound.deref().read()))
    x.add_source("rocket_ride", ride)
    cannonball(env, x)
    firestarter(env, x)


def cannonball(env, x):
    """The S.R.S. Cannon's charge hum (its pitch is set by the code), and its cannonball's sounds from where it is: its
    bounce off an enemy, its break (the break effect's own pitch is set by its RandomPitch, its dust's on its
    AudioSource) and its ground shockwave (the slam's). Its interruption explosion is heard everywhere, as the other
    explosions are (its pitch is set by its RandomPitch)."""
    launcher = find_class(env, "RocketLauncher", "Rocket Launcher Cannonball", variation=1)
    charge = source_ref(launcher.chargeSound)
    x.add_clip("rocket_cannonball_charge", charge.m_audioClip, charge.m_Volume)
    ball_go = launcher.cannonBall.deref().read().m_GameObject.read()
    ball = next(s for s in components(ball_go, "MonoBehaviour") if hasattr(s, "groundHitShockwave"))

    def placed(event, go, source, pitch=1.0):
        x.add_clip(event, source.m_audioClip, source.m_Volume, pitch, mono=True)
        tree = next(t for t in audio_sources_tree(go) if t["m_audioClip"]["m_PathID"] == source.m_audioClip.path_id)
        x.events[event]["sounds"][0]["attenuation_distance"] = round(tree["MaxDistance"] * BLOCKS_PER_UNIT)

    bounce_go = ball.bounceSound.deref().read().m_GameObject.read()
    placed("cannonball_bounce", bounce_go, next(components(bounce_go, "AudioSource")))
    break_go = ball.breakEffect.deref().read()
    placed("cannonball_break", break_go, next(components(break_go, "AudioSource")))
    dust = named_source(break_go, "Dust")
    placed("cannonball_break_dust", break_go, dust, dust.m_Pitch)
    shockwave_go = ball.groundHitShockwave.deref().read()
    shockwave = next(m for m in components(shockwave_go, "MonoBehaviour") if hasattr(m, "soundEffect"))
    sound_go = shockwave.soundEffect.deref().read()
    placed("cannonball_shockwave", sound_go, next(components(sound_go, "AudioSource")), next(components(sound_go, "AudioSource")).m_Pitch)
    explosion = next(audio_sources(ball.interruptionExplosion.deref().read()))
    x.add_clip("explosion_cannonball", explosion.m_audioClip, explosion.m_Volume)


def firestarter(env, x):
    """The Firestarter's spray: its start and its loop as it starts (their pitch is set by the code), its stop (the
    same), and the click when there isn't enough fuel to start."""
    launcher = find_class(env, "RocketLauncher", "Rocket Launcher Napalm", variation=2)
    loop, start = (source_ref(s) for s in launcher.napalmMuzzleFlashSounds)
    x.add_clip("rocket_napalm_spray", loop.m_audioClip, loop.m_Volume)
    x.add_clip("rocket_napalm_start", start.m_audioClip, start.m_Volume)
    stop = source_ref(launcher.napalmStopSound)
    x.add_clip("rocket_napalm_stop", stop.m_audioClip, stop.m_Volume)
    x.add_source("rocket_napalm_no_ammo", source_ref(launcher.napalmNoAmmoSound))


def jumpstart(env, x):
    """The JumpStart's chime when the zapper has recharged (its pitch is set on its AudioSource); the zapper's hum while
    it's attached (its pitch is set by its code), its pulse orb's crackle, its attach sound, its two warnings while the
    cable is too long or blocked (their pitch is set by its code) and the cable snapping. A zap's sparks, and the
    electric arc the zap goes along. Those that come from somewhere in the world fade out over their AudioSource's
    range."""
    jumpstart = find_class(env, "Nailgun", "Nailgun Zapper", altVersion=0)
    x.add_source("jumpstart_ready", next(components(jumpstart.m_GameObject.read(), "AudioSource")))
    zapper_go = jumpstart.zapper.deref().read().m_GameObject.read()
    zapper = next(s for s in components(zapper_go, "MonoBehaviour") if hasattr(s, "distanceWarningSounds"))

    def placed(event, go, source, volume=None, pitch=1.0):
        x.add_clip(event, source.m_audioClip, source.m_Volume if volume is None else volume, pitch, mono=True)
        tree = next(t for t in audio_sources_tree(go) if t["m_audioClip"]["m_PathID"] == source.m_audioClip.path_id)
        x.events[event]["sounds"][0]["attenuation_distance"] = round(tree["MaxDistance"] * BLOCKS_PER_UNIT)

    placed("zapper_hum", zapper_go, next(components(zapper_go, "AudioSource")))
    orb_go = zapper.lightningPulseOrb.deref().read().m_GameObject.read()
    placed("zapper_pulse", orb_go, next(components(orb_go, "AudioSource")), pitch=next(components(orb_go, "AudioSource")).m_Pitch)
    attach_go = zapper.attachSound.deref().read()
    placed("zapper_attach", attach_go, next(components(attach_go, "AudioSource")))
    tighten, warning = (source_ref(p) for p in zapper.distanceWarningSounds)
    placed("zapper_tighten", tighten.m_GameObject.read(), tighten)
    x.add_clip("zapper_warning", warning.m_audioClip, warning.m_Volume)
    snap_go = zapper.cableSnap.deref().read().m_GameObject.read()
    placed("zapper_snap", snap_go, next(components(snap_go, "AudioSource")))
    sparks = next(components(zapper.zapParticle.deref().read(), "AudioSource"))
    x.add_clip("zap", sparks.m_audioClip, sparks.m_Volume)
    references = next(o.read() for o in env.objects if o.type.name == "MonoBehaviour" and script_class(o) == "DefaultReferenceManager")
    line_go = references.electricLine.deref().read().m_GameObject.read()
    placed("electric_line", line_go, next(components(line_go, "AudioSource")))


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

    # Sliding on gasoline: its pitch goes up to 2.75, past Minecraft's highest, so it's baked in at 2 and the code
    # plays it at half its pitch.
    oil = source_ref(movement.oilSlideEffect)
    x.event("oil_slide", [sound(x.clip(oil.m_audioClip, speed=2), oil.m_Volume)])

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
