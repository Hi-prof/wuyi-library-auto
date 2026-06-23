from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path


SHANGHAI_TIMEZONE = timezone(timedelta(hours=8))


@dataclass(frozen=True)
class BuildVersion:
    version_code: int
    version_name: str
    build_marker: str


def read_version_file(version_file: Path) -> BuildVersion:
    payload: dict[str, str] = {}
    for raw_line in version_file.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if separator != "=":
            raise ValueError(f"版本文件格式错误：{raw_line}")
        payload[key.strip()] = value.strip()

    version_code = int(payload.get("versionCode", "").strip())
    version_name = payload.get("versionName", "").strip()
    build_marker = payload.get("buildMarker", "").strip()
    if not version_name:
        raise ValueError("版本文件缺少 versionName")
    if not build_marker:
        raise ValueError("版本文件缺少 buildMarker")
    return BuildVersion(
        version_code=version_code,
        version_name=version_name,
        build_marker=build_marker,
    )


def bump_version_name(version_name: str) -> str:
    parts = version_name.strip().split(".")
    if len(parts) not in {2, 3} or any(not part.isdigit() for part in parts):
        raise ValueError(f"仅支持 x.y 或 x.y.z 风格的纯数字版本号，当前为：{version_name}")
    if len(parts) == 2:
        major, minor = (int(part) for part in parts)
        return f"{major}.{minor + 1}.0"
    major, minor, patch = (int(part) for part in parts)
    return f"{major}.{minor}.{patch + 1}"


def build_marker_for(build_time: datetime) -> str:
    localized_time = build_time.astimezone(SHANGHAI_TIMEZONE)
    return localized_time.strftime("%Y-%m-%d-%H%M%S")


def write_version_file(version_file: Path, version: BuildVersion) -> None:
    version_file.write_text(
        "\n".join(
            (
                f"versionCode={version.version_code}",
                f"versionName={version.version_name}",
                f"buildMarker={version.build_marker}",
            )
        )
        + "\n",
        encoding="utf-8",
    )


def next_build_version(
    current_version: BuildVersion,
    *,
    build_time: datetime,
) -> BuildVersion:
    return BuildVersion(
        version_code=current_version.version_code + 1,
        version_name=bump_version_name(current_version.version_name),
        build_marker=build_marker_for(build_time),
    )


def bump_version_file(
    version_file: Path,
    *,
    build_time: datetime | None = None,
) -> BuildVersion:
    current_version = read_version_file(version_file)
    next_version = next_build_version(
        current_version,
        build_time=build_time or datetime.now(SHANGHAI_TIMEZONE),
    )
    write_version_file(version_file, next_version)
    return next_version


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="为 Android 构建准备新的版本号。")
    parser.add_argument(
        "--version-file",
        default=str(Path(__file__).resolve().parents[1] / "version.properties"),
        help="版本文件路径，默认是 library-android/version.properties",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="只计算下一版本，不写回文件。",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    version_file = Path(args.version_file).resolve()
    current_version = read_version_file(version_file)
    next_version = next_build_version(
        current_version,
        build_time=datetime.now(SHANGHAI_TIMEZONE),
    )
    if not args.dry_run:
        write_version_file(version_file, next_version)
    print(
        f"版本已准备：versionCode={next_version.version_code}, "
        f"versionName={next_version.version_name}, "
        f"buildMarker={next_version.build_marker}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
