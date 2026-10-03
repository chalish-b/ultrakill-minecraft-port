"""Where the local ULTRAKILL install lives. Override with the ULTRAKILL_DIR environment variable."""
import os
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

GAME = Path(os.environ.get(
    "ULTRAKILL_DIR",
    "~/Library/Application Support/CrossOver/Bottles/Steam/drive_c/Program Files (x86)/Steam/steamapps/common/ULTRAKILL",
)).expanduser()
DATA = GAME / "ULTRAKILL_Data"
MANAGED = DATA / "Managed"
BUNDLES = DATA / "StreamingAssets" / "aa" / "StandaloneWindows64"
