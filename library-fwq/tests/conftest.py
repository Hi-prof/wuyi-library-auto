from __future__ import annotations

import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
PREVENT_AUTO_SRC_PATH = PROJECT_ROOT / "library-fwq" / "src"
MAIN_PROJECT_SRC_PATH = PROJECT_ROOT / "library-window" / "src"

for path in (PREVENT_AUTO_SRC_PATH, MAIN_PROJECT_SRC_PATH):
    if str(path) not in sys.path:
        sys.path.insert(0, str(path))
