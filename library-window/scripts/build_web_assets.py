from __future__ import annotations

import argparse
import sys
from pathlib import Path


WEB_DIR = Path(__file__).resolve().parents[1] / "src" / "wuyi_seat_bot" / "web"
SOURCE_FILES = (
    "state.js",
    "seat_map.js",
    "tasks.js",
    "accounts.js",
    "api.js",
)
APP_JS_PATH = WEB_DIR / "app.js"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--check",
        action="store_true",
        help="只检查 app.js 是否和拆分源码一致",
    )
    args = parser.parse_args()
    bundle = build_app_js_bundle()
    if args.check:
        return check_app_js(bundle)
    APP_JS_PATH.write_text(bundle, encoding="utf-8", newline="\n")
    print(f"已生成 {APP_JS_PATH}")
    return 0


def build_app_js_bundle() -> str:
    bundle_parts: list[str] = []
    for file_name in SOURCE_FILES:
        source_path = WEB_DIR / file_name
        bundle_parts.append(source_path.read_text(encoding="utf-8").rstrip())
    return "\n\n".join(bundle_parts) + "\n"


def check_app_js(expected: str) -> int:
    if not APP_JS_PATH.exists():
        print(f"缺少 {APP_JS_PATH}", file=sys.stderr)
        return 1
    current = APP_JS_PATH.read_text(encoding="utf-8")
    if current != expected:
        print(
            "app.js 和拆分源码不一致，请运行：python scripts/build_web_assets.py",
            file=sys.stderr,
        )
        return 1
    print("app.js 已和拆分源码保持一致")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
