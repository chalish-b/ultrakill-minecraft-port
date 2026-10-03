# ULTRACRAFT

ULTRAKILL's movement and arsenal in Minecraft, as a Fabric mod. Private project.

## Layout

| Folder | |
|---|---|
| `mod/` | The Fabric mod (Minecraft 26.3, Java 25) |
| `tools/` | Extraction scripts for ULTRAKILL's code and data |
| `reference/` | Decompiled ULTRAKILL code, the player prefab as JSON, Minecraft sources. Not committed; rebuild it with `tools/extract.sh` |

## Setup

You need JDK 25 (`brew install openjdk@25`), and for `tools/`: .NET with `ilspycmd`, and `uv`.
Gradle needs `JAVA_HOME` pointing at JDK 25:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

Run the game with the mod:

```bash
cd mod && ./gradlew runClient
```

## Playing

Singleplayer. ULTRAKILL movement is on by default and switches to Minecraft's in deep water, lava,
on ladders, while flying, gliding or riding.

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
`fovEffects`, `speedometer`, `debug`, `infiniteStamina`.

## Rebuilding reference/

Rebuild `reference/` (reads the ULTRAKILL install in the CrossOver Steam bottle; set
`ULTRAKILL_DIR` to use another one):

```bash
tools/extract.sh
```
