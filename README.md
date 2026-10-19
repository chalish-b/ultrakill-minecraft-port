# ULTRACRAFT

ULTRAKILL's movement and arsenal in Minecraft, as a Fabric mod.

An unofficial fan project, not affiliated with or endorsed by Arsi "Hakita" Patala or New Blood
Interactive. The repo contains no ULTRAKILL assets or code: you need your own copy of ULTRAKILL, and
`tools/extract.sh` pulls what the mod needs from it.

## Layout

| Folder | |
|---|---|
| `mod/` | The Fabric mod (Minecraft 26.3, Java 25) |
| `tools/` | Extraction scripts for ULTRAKILL's code and data |
| `reference/` | Decompiled ULTRAKILL code, the player prefab as JSON, Minecraft sources. Not committed |
| `mod/src/generated/` | ULTRAKILL's sounds, packaged into the mod. Not committed |

## Setup

You need JDK 25 (`brew install openjdk@25`), and for `tools/`: .NET with `ilspycmd`, and `uv`.
Gradle needs `JAVA_HOME` pointing at JDK 25:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

Extract from your ULTRAKILL install once (and again after a game update), then run the game with the mod:

```bash
tools/extract.sh
```

```bash
cd mod && ./gradlew runClient
```

## Playing

Singleplayer. ULTRAKILL movement is on by default and switches to Minecraft's while riding.

| Key | |
|---|---|
| U | Toggle ULTRAKILL movement |
| Shift | Dash |
| Ctrl | Slide (on the ground) / slam (in the air) |
| Space | Jump, wall jump (3 before landing) |
| Left Alt | Sneak |

All rebindable under Controls → ULTRACRAFT. `/ultracraft` shows the settings, and
`/ultracraft <setting> <value>` changes one: `speedScale` (1 = ULTRAKILL relative to your body,
1.944 = one ULTRAKILL unit per block), `stepMode ultrakill|minecraft`, `cameraTilt`, `screenShake`,
`fovEffects`, `speedometer`, `debug`, `infiniteStamina`, `ultrakillFootsteps`.

## Extraction

`tools/extract.sh` reads the ULTRAKILL install in the CrossOver Steam bottle (set `ULTRAKILL_DIR` to
use another one) and rebuilds `reference/` and `mod/src/generated/`. `tools/extract_sounds.py` redoes
just the sounds.

The defaults (game folder, `JAVA_HOME`, `DOTNET_ROOT`) are macOS paths with Homebrew and CrossOver. On
Windows or Linux, point them at your own installs, e.g.:

```bash
ULTRAKILL_DIR="$HOME/.steam/steam/steamapps/common/ULTRAKILL" tools/extract.sh
```

## License

The mod's code is MIT licensed (see `LICENSE`). ULTRAKILL and its assets belong to their owners.
