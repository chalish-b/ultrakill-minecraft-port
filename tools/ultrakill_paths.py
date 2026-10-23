"""Where the local ULTRAKILL install lives. Override with the ULTRAKILL_DIR environment variable.

Without it, the install is looked up in Steam's libraries: the default Steam folder on Windows, the native and
Flatpak Steam folders on Linux, and the Steam folders of CrossOver bottles on macOS.
"""
import os
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent


def steam_roots():
    home = Path.home()
    if sys.platform == "win32":
        return [Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / "Steam"]
    if sys.platform == "darwin":
        bottles = home / "Library" / "Application Support" / "CrossOver" / "Bottles"
        return sorted(bottles.glob("*/drive_c/Program Files (x86)/Steam"))
    return [
        home / ".steam" / "steam",
        home / ".local" / "share" / "Steam",
        home / ".var" / "app" / "com.valvesoftware.Steam" / ".local" / "share" / "Steam",
    ]


def steam_libraries(root):
    """The Steam folder itself, then the other libraries listed in its libraryfolders.vdf."""
    yield root
    vdf = root / "steamapps" / "libraryfolders.vdf"
    if vdf.is_file():
        for path in re.findall(r'"path"\s+"([^"]+)"', vdf.read_text(errors="replace")):
            yield Path(path.replace("\\\\", "\\"))


def find_game():
    if "ULTRAKILL_DIR" in os.environ:
        return Path(os.environ["ULTRAKILL_DIR"]).expanduser()
    for root in steam_roots():
        for library in steam_libraries(root):
            game = library / "steamapps" / "common" / "ULTRAKILL"
            if (game / "ULTRAKILL_Data").is_dir():
                return game
    sys.exit("ULTRAKILL not found in your Steam libraries. Set ULTRAKILL_DIR to its install folder.")


GAME = find_game()
DATA = GAME / "ULTRAKILL_Data"
MANAGED = DATA / "Managed"
BUNDLES = DATA / "StreamingAssets" / "aa" / "StandaloneWindows64"
