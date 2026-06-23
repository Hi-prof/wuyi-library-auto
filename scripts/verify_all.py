from __future__ import annotations

import os
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class CheckCommand:
    name: str
    cwd: Path
    command: list[str]
    env: dict[str, str]


def main() -> int:
    commands = build_commands()
    for check in commands:
        print(f"==> {check.name}", flush=True)
        completed = subprocess.run(
            check.command,
            cwd=check.cwd,
            env={**os.environ, **check.env},
            text=True,
        )
        if completed.returncode != 0:
            print(f"失败：{check.name}", file=sys.stderr, flush=True)
            return completed.returncode
    print("全部检查通过", flush=True)
    return 0


def build_commands() -> list[CheckCommand]:
    java_env = {
        "_JAVA_OPTIONS": "-Xms128m -Xmx1024m -XX:+UseSerialGC",
        "GRADLE_OPTS": "-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8",
    }
    return [
        CheckCommand(
            name="Windows 前端资源同步检查",
            cwd=ROOT_DIR / "library-window",
            command=["uv", "run", "python", "scripts/build_web_assets.py", "--check"],
            env={"PYTHONUTF8": "1"},
        ),
        CheckCommand(
            name="Windows 客户端测试",
            cwd=ROOT_DIR / "library-window",
            command=["uv", "run", "--extra", "test", "python", "-m", "pytest"],
            env={"PYTHONUTF8": "1"},
        ),
        CheckCommand(
            name="服务端测试",
            cwd=ROOT_DIR / "library-fwq",
            command=["uv", "run", "--extra", "test", "python", "-m", "pytest"],
            env={
                "PYTHONUTF8": "1",
                "PYTHONPATH": str(ROOT_DIR / "library-window" / "src") + os.pathsep + "src",
            },
        ),
        CheckCommand(
            name="Android 单元测试",
            cwd=ROOT_DIR / "library-android",
            command=[
                str(ROOT_DIR / "library-android" / "gradlew.bat"),
                "--no-daemon",
                "--max-workers=1",
                "--console=plain",
                "testDebugUnitTest",
            ],
            env=java_env,
        ),
    ]


if __name__ == "__main__":
    raise SystemExit(main())
