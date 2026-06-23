from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parents[1]
DEFAULT_AUTH_PASSWORD = "WuyiAuto!2026#SrvPwd9QmVx2Lp"
DEFAULT_SESSION_SECRET = "WuyiAuto!2026#SessionKey7RbTn4YpZs8"


def main() -> int:
    checks = (
        check_android_release_signing,
        check_windows_exe_smoke,
        check_server_auth_override,
    )
    failures = []
    for check in checks:
        name, ok, message = check()
        status = "通过" if ok else "失败"
        print(f"[{status}] {name}: {message}")
        if not ok:
            failures.append(name)
    if failures:
        print("发布前检查未通过：" + ", ".join(failures), file=sys.stderr)
        return 1
    print("发布前检查通过")
    return 0


def check_android_release_signing() -> tuple[str, bool, str]:
    props_path = ROOT_DIR / "library-android" / "keystore.properties"
    if not props_path.exists():
        return "Android release signing", False, "缺少 library-android/keystore.properties"
    props = read_properties(props_path)
    required = ("storeFile", "storePassword", "keyAlias", "keyPassword")
    missing = [key for key in required if not props.get(key) or "<" in props[key]]
    if missing:
        return "Android release signing", False, "字段未配置：" + ", ".join(missing)
    store_file = ROOT_DIR / "library-android" / props["storeFile"]
    if not store_file.exists():
        return "Android release signing", False, f"keystore 不存在：{store_file}"
    return "Android release signing", True, "release 签名配置可用"


def check_windows_exe_smoke() -> tuple[str, bool, str]:
    exe_path = ROOT_DIR / "dist" / "exe" / "wuyi-seat-bot" / "wuyi-seat-bot.exe"
    if not exe_path.exists():
        return "Windows exe smoke", False, f"缺少 {exe_path}"
    completed = subprocess.run(
        [str(exe_path), "--help"],
        cwd=exe_path.parent,
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=20,
    )
    if completed.returncode != 0:
        return "Windows exe smoke", False, completed.stderr.strip() or "--help 运行失败"
    if "武夷学院" not in completed.stdout:
        return "Windows exe smoke", False, "--help 输出不符合预期"
    return "Windows exe smoke", True, "exe 可以启动并输出帮助"


def check_server_auth_override() -> tuple[str, bool, str]:
    password = os.environ.get("PREVENT_AUTO_AUTH_PASSWORD", "")
    secret = os.environ.get("PREVENT_AUTO_SESSION_SECRET", "")
    if not password or password == DEFAULT_AUTH_PASSWORD:
        return "Server auth override", False, "必须设置非默认 PREVENT_AUTO_AUTH_PASSWORD"
    if not secret or secret == DEFAULT_SESSION_SECRET:
        return "Server auth override", False, "必须设置非默认 PREVENT_AUTO_SESSION_SECRET"
    if len(password) < 20:
        return "Server auth override", False, "PREVENT_AUTO_AUTH_PASSWORD 至少 20 个字符"
    if len(secret) < 20:
        return "Server auth override", False, "PREVENT_AUTO_SESSION_SECRET 至少 20 个字符"
    return "Server auth override", True, "服务端认证密钥已覆盖默认值"


def read_properties(path: Path) -> dict[str, str]:
    props: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        text = line.strip()
        if not text or text.startswith("#") or "=" not in text:
            continue
        key, value = text.split("=", 1)
        props[key.strip()] = value.strip()
    return props


if __name__ == "__main__":
    raise SystemExit(main())
