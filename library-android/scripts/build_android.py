from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

from prepare_android_version import bump_version_file


TASK_BY_TARGET = {
    "debug": ":app:assembleDebug",
    "release": ":app:assembleRelease",
    "bundleRelease": ":app:bundleRelease",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="自动递增版本号并执行 Android 打包。")
    parser.add_argument(
        "target",
        nargs="?",
        default="debug",
        choices=tuple(TASK_BY_TARGET),
        help="打包目标，默认 debug。",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root_dir = Path(__file__).resolve().parents[1]
    version_file = root_dir / "version.properties"
    backup_bytes = version_file.read_bytes()

    next_version = bump_version_file(version_file)
    task = TASK_BY_TARGET[args.target]
    print(
        f"开始打包：{task}，"
        f"versionCode={next_version.version_code}，"
        f"versionName={next_version.version_name}，"
        f"buildMarker={next_version.build_marker}"
    )

    try:
        subprocess.run(
            [str(root_dir / "gradlew.bat"), "--no-daemon", "--max-workers=1", task],
            cwd=root_dir,
            check=True,
        )
    except subprocess.CalledProcessError:
        version_file.write_bytes(backup_bytes)
        print("打包失败，已恢复 version.properties。")
        return 1

    print("打包完成。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
