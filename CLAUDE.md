# ULTRAKILL Minecraft Port

Ports ULTRAKILL's player controller and weapons into Minecraft (Fabric, 26.3). Singleplayer only.
See `README.md` for layout and setup.

- The repo is public on GitHub. Decompiled code and extracted assets live only in `reference/` and
  `mod/src/generated/` (both gitignored) and are never committed or copied verbatim into tracked files
  outside the mod's port.
- `reference/decomp/` is the source of truth for ULTRAKILL behaviour (`NewMovement.cs` is the
  player controller). `reference/player/` holds the serialized values (speeds, collider sizes,
  gravity, timestep) — read constants from there instead of guessing.
- `reference/minecraft/` holds Minecraft's sources for writing mixins against.
- Gradle needs `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`.
- Don't launch Minecraft (`runClient`); the user runs and playtests it. `./gradlew build` is fine.
