"""原生设置窗口模块。"""

from __future__ import annotations

import os
import sys
from pathlib import Path


def ensure_tk_runtime() -> None:
    if _has_tk_runtime():
        return
    for root in _candidate_tcl_roots():
        tcl_dir = root / "tcl8.6"
        tk_dir = root / "tk8.6"
        if not _is_valid_runtime_dir(tcl_dir, "init.tcl") or not _is_valid_runtime_dir(tk_dir, "tk.tcl"):
            continue
        os.environ["TCL_LIBRARY"] = str(tcl_dir)
        os.environ["TK_LIBRARY"] = str(tk_dir)
        return


def _has_tk_runtime() -> bool:
    tcl_library = os.environ.get("TCL_LIBRARY")
    tk_library = os.environ.get("TK_LIBRARY")
    return bool(
        tcl_library
        and tk_library
        and _is_valid_runtime_dir(Path(tcl_library), "init.tcl")
        and _is_valid_runtime_dir(Path(tk_library), "tk.tcl")
    )


def _candidate_tcl_roots() -> list[Path]:
    resolved_base = Path(sys.base_prefix).resolve()
    resolved_executable = Path(sys.executable).resolve()
    roots: list[Path] = []
    if resolved_base.name == "current" and resolved_base.parent.exists():
        for child in sorted(resolved_base.parent.iterdir(), reverse=True):
            if child.name == "current":
                continue
            roots.append(child / "tcl")
    roots.extend(
        [
            resolved_executable.parent / "tcl",
            resolved_base / "tcl",
            Path.home() / "AppData" / "Roaming" / "uv" / "python",
        ]
    )
    candidates: list[Path] = []
    for root in roots:
        if root.name == "python" and root.exists():
            for child in sorted(root.iterdir(), reverse=True):
                candidate = child / "tcl"
                if candidate.exists():
                    candidates.append(candidate)
            continue
        candidates.append(root)
    return candidates


def _is_valid_runtime_dir(path: Path, marker: str) -> bool:
    return path.exists() and (path / marker).exists()


ensure_tk_runtime()
