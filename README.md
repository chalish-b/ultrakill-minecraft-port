# ULTRAKILL Minecraft Port

> [!note]
> This project was 100% vibe coded with Claude. It was a fun experiement to see how well it can do something like this. It still made a lot of mistakes during the process, and I had to give feedback on what feels wrong or what's missing as an ULTRAKILL player.
>
> Unlike other similar projects where both games are run simultaneously and communicate with each other, this one is just a plain Minecraft Fabric mod that ports ULTRAKILL's movement and weapons. So the mechanics might not be exact 1:1, but it also means you don't need to have two games running at the same time, it's just Minecraft.
>
> There aren't any plans to release this as a proper mod. If you want to, just ask your AI to build it or something. Do whatever you want, as long as you credit the original creators (of ULTRAKILL, not me)
>
>  (This was the only thing written by a human in this project. Everything after this point is Claude slop)

ULTRAKILL's movement and arsenal in Minecraft, as a Fabric mod.

An unofficial fan project, not affiliated with or endorsed by Arsi "Hakita" Patala or New Blood
Interactive. The repo contains no ULTRAKILL assets or code: you need your own copy of ULTRAKILL, and
`tools/extract.py` pulls what the mod needs from it.

## Layout

| Folder | |
|---|---|
| `mod/` | The Fabric mod (Minecraft 26.3, Java 25) |
| `tools/` | Extraction scripts for ULTRAKILL's code and data |
| `reference/` | Decompiled ULTRAKILL code, the player prefab as JSON, Minecraft sources. Not committed |
| `mod/src/generated/` | ULTRAKILL's sounds, textures, fonts and viewmodels, packaged into the mod. Not committed |

## Setup

You need JDK 25, the .NET 10 SDK with `ilspycmd`, and [uv](https://docs.astral.sh/uv/).

| OS | |
|---|---|
| macOS | `brew install openjdk@25 dotnet uv` |
| Windows | `winget install Microsoft.OpenJDK.25 Microsoft.DotNet.SDK.10 astral-sh.uv` |
| Linux | JDK 25 and the .NET 10 SDK from your distro's packages, uv from its install script |

Then install `ilspycmd`:

```bash
dotnet tool install -g ilspycmd
```

Gradle runs on the JDK in `JAVA_HOME`, or on the `java` in your `PATH`, which has to be JDK 25. Homebrew's
JDK is on neither, so on macOS:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

Extract from your ULTRAKILL install once (and again after a game update), then run the game with the mod:

```bash
uv run tools/extract.py
```

```bash
cd mod
./gradlew runClient    # Windows: .\gradlew runClient
```

## Playing

Singleplayer. ULTRAKILL movement is on by default.

| Key | |
|---|---|
| U | Toggle ULTRAKILL movement |
| Shift | Dash |
| Ctrl | Slide (on the ground) / slam (in the air) |
| Space | Jump, wall jump (3 before landing) |
| Left Alt | Sneak |
| Left click | Fire |
| Right click | Alt fire |
| F | Punch (Feedbacker) |
| G | Punch (Knuckleblaster) |
| R | Whiplash |
| X / C | Variation slot 2 / 3 |
| Unbound | Variation slot 1, next / previous variation, last used weapon |
| K | Weapon loadout |

All rebindable under Controls → ULTRAKILL Minecraft Port. `/ultracraft` shows the settings, and
`/ultracraft <setting> <value>` changes one: `speedScale` (default 0.85; 1 = ULTRAKILL relative to your body,
1.944 = one ULTRAKILL unit per block), `stepMode ultrakill|minecraft`, `cameraTilt`, `screenShake`,
`fovEffects`, `speedometer`, `debug`, `infiniteStamina`, `ultrakillFootsteps`.

## Extraction

`tools/extract.py` finds ULTRAKILL in your Steam libraries and rebuilds `reference/` and
`mod/src/generated/`. On macOS, it looks in the Steam installs inside your CrossOver bottles. `tools/extract_sounds.py`
redoes just the sounds.

If ULTRAKILL isn't found, set `ULTRAKILL_DIR` to its install folder:

```bash
ULTRAKILL_DIR="/path/to/ULTRAKILL" uv run tools/extract.py
```

```powershell
$env:ULTRAKILL_DIR = "D:\Games\ULTRAKILL"; uv run tools/extract.py
```

## License

The mod's code is MIT licensed (see `LICENSE`). ULTRAKILL and its assets belong to their owners.
