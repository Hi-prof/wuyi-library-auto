from __future__ import annotations

from pathlib import Path


_SRC_PACKAGE_DIR = Path(__file__).resolve().parent.parent / "src" / "wuyi_seat_bot"
__path__ = [str(_SRC_PACKAGE_DIR)]

_init_file = _SRC_PACKAGE_DIR / "__init__.py"
exec(_init_file.read_text(encoding="utf-8"), globals())
