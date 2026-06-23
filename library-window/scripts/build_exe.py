from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

DEFAULT_TIMESTAMP_URL = "http://timestamp.digicert.com"
SIGN_CERT_FILE_ENV = "WUYI_SIGN_CERT_FILE"
SIGN_CERT_PASSWORD_ENV = "WUYI_SIGN_CERT_PASSWORD"
SIGN_CERT_THUMBPRINT_ENV = "WUYI_SIGN_CERT_THUMBPRINT"
SIGN_TIMESTAMP_URL_ENV = "WUYI_SIGN_TIMESTAMP_URL"
SIGNTOOL_PATH_ENV = "WUYI_SIGNTOOL_PATH"
DIST_EXE_DIRECTORY_NAME = "exe"


@dataclass(frozen=True)
class SigningConfig:
    signtool_path: Path
    timestamp_url: str
    cert_file: Path | None
    cert_password: str | None
    cert_thumbprint: str | None


def _ensure_utf8_console() -> None:
    for stream_name in ("stdout", "stderr"):
        stream = getattr(sys, stream_name, None)
        if stream is not None and hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="构建武夷学院自习室自动预约工具的 Windows exe")
    parser.add_argument("--name", default="wuyi-seat-bot", help="生成的 exe 名称，默认 wuyi-seat-bot")
    parser.add_argument(
        "--version",
        help="显式指定产物版本；不传时读取 src/wuyi_seat_bot/version.py 里的 __version__",
    )
    parser.add_argument(
        "--bump-version",
        action="store_true",
        help="打包前自动递增 src/wuyi_seat_bot/version.py 里的版本号",
    )
    parser.add_argument(
        "--icon",
        help="exe 图标路径；不传时默认使用项目里的 assets/app-icon.ico",
    )
    parser.add_argument(
        "--onefile",
        action="store_true",
        help="改用单文件模式；默认使用 onedir，启动更快、守护重启成本更低",
    )
    parser.add_argument(
        "--sign",
        action="store_true",
        help="要求对 exe 做正式签名；缺少证书或 signtool 会直接失败",
    )
    parser.add_argument(
        "--sign-cert-file",
        help=f"PFX 证书路径；也可用环境变量 {SIGN_CERT_FILE_ENV}",
    )
    parser.add_argument(
        "--sign-cert-password",
        help=f"PFX 证书密码；也可用环境变量 {SIGN_CERT_PASSWORD_ENV}",
    )
    parser.add_argument(
        "--sign-cert-thumbprint",
        help=f"证书指纹；也可用环境变量 {SIGN_CERT_THUMBPRINT_ENV}",
    )
    parser.add_argument(
        "--timestamp-url",
        help=f"时间戳服务地址；默认 {DEFAULT_TIMESTAMP_URL}，也可用环境变量 {SIGN_TIMESTAMP_URL_ENV}",
    )
    parser.add_argument(
        "--signtool",
        help=f"signtool.exe 路径；也可用环境变量 {SIGNTOOL_PATH_ENV}",
    )
    return parser


def build_add_data_argument(source_path: Path, target_path: str) -> str:
    separator = ";" if os.name == "nt" else ":"
    return f"{source_path}{separator}{target_path}"


def build_pyinstaller_command(
    project_root: Path,
    *,
    name: str,
    onefile: bool,
    entry_script: Path,
    icon_path: Path | None,
    include_package_data: bool,
    include_desktop_runtime: bool,
    version_file_path: Path | None = None,
    spec_path: Path | None = None,
) -> list[str]:
    mode_flag = "--onefile" if onefile else "--onedir"
    dist_path = build_dist_path(project_root)
    command = [
        sys.executable,
        "-m",
        "PyInstaller",
        "--noconfirm",
        "--clean",
        "--distpath",
        str(dist_path),
        "--name",
        name,
        "--paths",
        str(project_root / "src"),
    ]
    if icon_path is not None:
        command.extend(["--icon", str(icon_path)])
    if version_file_path is not None:
        command.extend(["--version-file", str(version_file_path)])
    if spec_path is not None:
        command.extend(["--specpath", str(spec_path)])
    if include_package_data:
        command.extend(["--collect-data", "wuyi_seat_bot"])
        command.extend(
            [
                "--add-data",
                build_add_data_argument(
                    project_root / "src" / "wuyi_seat_bot" / "web",
                    "wuyi_seat_bot/web",
                ),
            ]
        )
    if include_desktop_runtime:
        command.extend(
            [
                "--collect-submodules",
                "PIL",
                "--collect-submodules",
                "pystray",
            ]
        )
    command.extend([mode_flag, str(entry_script)])
    return command


def build_dist_path(project_root: Path) -> Path:
    return project_root.parent / "dist" / DIST_EXE_DIRECTORY_NAME


def build_output_path(project_root: Path, *, name: str, onefile: bool) -> Path:
    dist_path = build_dist_path(project_root)
    if onefile:
        return dist_path / f"{name}.exe"
    return dist_path / name / f"{name}.exe"


def reset_output_path(*, output_path: Path, onefile: bool) -> None:
    cleanup_target = output_path if onefile else output_path.parent
    if cleanup_target.is_dir():
        shutil.rmtree(cleanup_target)
        return
    if cleanup_target.exists():
        cleanup_target.unlink()


def prepare_output_support_files(project_root: Path, *, output_path: Path) -> Path:
    output_directory = output_path.parent
    output_directory.mkdir(parents=True, exist_ok=True)
    (output_directory / "runtime" / "logs").mkdir(parents=True, exist_ok=True)

    config_path = output_directory / "config.json"
    _write_example_config(project_root, config_path)
    return config_path


def _write_example_config(project_root: Path, config_path: Path) -> Path:
    src_root = project_root / "src"
    src_root_text = str(src_root)
    if src_root_text not in sys.path:
        sys.path.insert(0, src_root_text)

    from wuyi_seat_bot.config import write_example_config

    return write_example_config(config_path, overwrite=True)


def copy_icon_to_output(*, output_path: Path, icon_path: Path | None) -> Path | None:
    if icon_path is None:
        return None

    target_directory = output_path.parent
    copied_icon_path = target_directory / icon_path.name
    copied_icon_path.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(icon_path, copied_icon_path)
    return copied_icon_path


def resolve_icon_path(project_root: Path, icon_value: str | None) -> Path | None:
    if icon_value:
        icon_path = Path(icon_value).expanduser().resolve()
        if not icon_path.exists():
            raise FileNotFoundError(f"未找到图标文件：{icon_path}")
        return icon_path

    default_icon_path = project_root / "assets" / "app-icon.ico"
    if default_icon_path.exists():
        return default_icon_path
    return None


def build_project_version_path(project_root: Path) -> Path:
    return project_root / "src" / "wuyi_seat_bot" / "version.py"


def load_project_version(project_root: Path) -> str:
    version_module_path = build_project_version_path(project_root)
    if not version_module_path.exists():
        raise FileNotFoundError(f"未找到版本文件：{version_module_path}")
    content = version_module_path.read_text(encoding="utf-8")
    match = re.search(r'^__version__\s*=\s*"([^"]+)"', content, re.MULTILINE)
    if match is None:
        raise ValueError(f"无法从 {version_module_path} 解析 __version__")
    return match.group(1)


def parse_numeric_version_parts(version: str) -> list[str]:
    raw_parts = [part.strip() for part in version.split(".")]
    if not raw_parts or any(not part or not part.isdigit() for part in raw_parts):
        raise ValueError(f"版本号必须是纯数字点分格式：{version}")
    return [str(int(part)) for part in raw_parts]


def bump_version_value(version: str) -> str:
    parts = parse_numeric_version_parts(version)
    parts[-1] = str(int(parts[-1]) + 1)
    return ".".join(parts)


def bump_project_version(project_root: Path) -> str:
    version_module_path = build_project_version_path(project_root)
    current_version = load_project_version(project_root)
    bumped_version = bump_version_value(current_version)
    content = version_module_path.read_text(encoding="utf-8")
    updated_content = re.sub(
        r'^(__version__\s*=\s*")[^"]+(")',
        rf"\g<1>{bumped_version}\2",
        content,
        count=1,
        flags=re.MULTILINE,
    )
    version_module_path.write_text(updated_content, encoding="utf-8")
    return bumped_version


def build_windows_file_version(version: str) -> str:
    parts = parse_numeric_version_parts(version)
    if len(parts) > 4:
        raise ValueError(f"Windows 文件版本最多支持 4 段：{version}")
    while len(parts) < 4:
        parts.append("0")
    return ".".join(parts)


def build_version_file_content(*, name: str, version: str) -> str:
    windows_file_version = build_windows_file_version(version)
    version_tuple = ", ".join(windows_file_version.split("."))
    return (
        "VSVersionInfo(\n"
        "  ffi=FixedFileInfo(\n"
        f"    filevers=({version_tuple}),\n"
        f"    prodvers=({version_tuple}),\n"
        "    mask=0x3F,\n"
        "    flags=0x0,\n"
        "    OS=0x40004,\n"
        "    fileType=0x1,\n"
        "    subtype=0x0,\n"
        "    date=(0, 0)\n"
        "  ),\n"
        "  kids=[\n"
        "    StringFileInfo([\n"
        "      StringTable(\n"
        "        '040904B0',\n"
        "        [\n"
        "          StringStruct('CompanyName', 'Wuyi Library Auto'),\n"
        "          StringStruct('FileDescription', '武夷学院自习室座位预约自动化工具'),\n"
        f"          StringStruct('FileVersion', '{version}'),\n"
        f"          StringStruct('InternalName', '{name}'),\n"
        "          StringStruct('LegalCopyright', 'Copyright (c) Wuyi Library Auto'),\n"
        f"          StringStruct('OriginalFilename', '{name}.exe'),\n"
        "          StringStruct('ProductName', 'Wuyi Seat Bot'),\n"
        f"          StringStruct('ProductVersion', '{version}')\n"
        "        ]\n"
        "      )\n"
        "    ]),\n"
        "    VarFileInfo([VarStruct('Translation', [1033, 1200])])\n"
        "  ]\n"
        ")\n"
    )


def write_version_file(*, version_file_path: Path, name: str, version: str) -> Path:
    version_file_path.parent.mkdir(parents=True, exist_ok=True)
    version_file_path.write_text(
        build_version_file_content(name=name, version=version),
        encoding="utf-8",
    )
    return version_file_path


def resolve_signtool_path(signtool_value: str | None) -> Path:
    if signtool_value:
        signtool_path = Path(signtool_value).expanduser().resolve()
        if not signtool_path.exists():
            raise FileNotFoundError(f"未找到 signtool.exe：{signtool_path}")
        return signtool_path

    windows_kits_root = Path(r"C:\Program Files (x86)\Windows Kits\10\bin")
    candidate_paths = sorted(windows_kits_root.glob(r"**\x64\signtool.exe"), reverse=True)
    if candidate_paths:
        return candidate_paths[0].resolve()
    fallback_path = Path(r"C:\Program Files (x86)\Windows Kits\10\App Certification Kit\signtool.exe")
    if fallback_path.exists():
        return fallback_path.resolve()
    raise FileNotFoundError("未找到 signtool.exe，请通过 --signtool 或环境变量指定路径")


def build_signtool_command(
    *,
    signtool_path: Path,
    file_path: Path,
    timestamp_url: str,
    cert_file: Path | None,
    cert_password: str | None,
    cert_thumbprint: str | None,
) -> list[str]:
    command = [
        str(signtool_path),
        "sign",
        "/fd",
        "SHA256",
        "/td",
        "SHA256",
        "/tr",
        timestamp_url,
    ]
    if cert_file is not None:
        command.extend(["/f", str(cert_file)])
        if cert_password:
            command.extend(["/p", cert_password])
    elif cert_thumbprint:
        command.extend(["/sha1", cert_thumbprint, "/sm"])
    else:
        raise ValueError("正式签名必须提供 PFX 证书文件或证书指纹")
    command.append(str(file_path))
    return command


def resolve_signing_config(
    project_root: Path,
    args: argparse.Namespace,
) -> SigningConfig | None:
    cert_file_value = args.sign_cert_file or os.environ.get(SIGN_CERT_FILE_ENV)
    cert_password = args.sign_cert_password or os.environ.get(SIGN_CERT_PASSWORD_ENV)
    cert_thumbprint = args.sign_cert_thumbprint or os.environ.get(SIGN_CERT_THUMBPRINT_ENV)
    timestamp_url = args.timestamp_url or os.environ.get(SIGN_TIMESTAMP_URL_ENV) or DEFAULT_TIMESTAMP_URL
    signtool_value = args.signtool or os.environ.get(SIGNTOOL_PATH_ENV)
    signing_requested = bool(args.sign or cert_file_value or cert_thumbprint)

    if not signing_requested:
        return None
    if cert_file_value and cert_thumbprint:
        raise ValueError("签名配置不能同时提供证书文件和证书指纹")

    cert_file: Path | None = None
    if cert_file_value:
        cert_file = Path(cert_file_value).expanduser()
        if not cert_file.is_absolute():
            cert_file = (project_root / cert_file).resolve()
        else:
            cert_file = cert_file.resolve()
        if not cert_file.exists():
            raise FileNotFoundError(f"未找到签名证书：{cert_file}")
    elif not cert_thumbprint:
        raise ValueError("已要求正式签名，但没有提供证书文件或证书指纹")

    return SigningConfig(
        signtool_path=resolve_signtool_path(signtool_value),
        timestamp_url=timestamp_url,
        cert_file=cert_file,
        cert_password=cert_password,
        cert_thumbprint=cert_thumbprint,
    )


def sign_output(output_path: Path, signing_config: SigningConfig) -> None:
    command = build_signtool_command(
        signtool_path=signing_config.signtool_path,
        file_path=output_path,
        timestamp_url=signing_config.timestamp_url,
        cert_file=signing_config.cert_file,
        cert_password=signing_config.cert_password,
        cert_thumbprint=signing_config.cert_thumbprint,
    )
    subprocess.run(command, check=True)


def main(argv: list[str] | None = None) -> int:
    _ensure_utf8_console()
    args = build_parser().parse_args(argv)
    if args.version and args.bump_version:
        raise ValueError("--version 和 --bump-version 不能同时使用")

    project_root = Path(__file__).resolve().parents[1]
    if args.bump_version:
        version = bump_project_version(project_root)
    else:
        version = args.version or load_project_version(project_root)
    icon_path = resolve_icon_path(project_root, args.icon)
    signing_config = resolve_signing_config(project_root, args)

    print("当前正在构建纯接口版主程序，不再包含 Playwright 或浏览器助手。")
    print(f"本次产物版本：{version}")
    if icon_path is not None:
        print(f"本次将使用 exe 图标：{icon_path}")
    if signing_config is not None:
        print(f"本次将使用正式签名：{signing_config.signtool_path}")
    output_path = build_output_path(project_root, name=args.name, onefile=args.onefile)
    reset_output_path(output_path=output_path, onefile=args.onefile)
    with tempfile.TemporaryDirectory(prefix="wuyi-seat-bot-build-") as temp_dir:
        temp_build_path = Path(temp_dir)
        version_file_path = write_version_file(
            version_file_path=temp_build_path / "pyinstaller-version-info.txt",
            name=args.name,
            version=version,
        )
        command = build_pyinstaller_command(
            project_root,
            name=args.name,
            onefile=args.onefile,
            entry_script=project_root / "src" / "wuyi_seat_bot" / "cli.py",
            icon_path=icon_path,
            include_package_data=True,
            include_desktop_runtime=True,
            version_file_path=version_file_path,
            spec_path=temp_build_path,
        )
        print("正在执行 PyInstaller 打包，请稍等。")
        subprocess.run(command, check=True, cwd=project_root)

    config_path = prepare_output_support_files(project_root, output_path=output_path)
    copied_icon_path = copy_icon_to_output(output_path=output_path, icon_path=icon_path)
    if signing_config is not None:
        print("正在执行正式签名，请稍等。")
        sign_output(output_path, signing_config)
    print(f"主程序打包完成：{output_path}")
    print(f"示例配置已重建：{config_path}")
    if signing_config is not None:
        print("正式签名已完成。")
    if copied_icon_path is not None:
        print(f"图标文件已同步到：{copied_icon_path}")
    if not args.onefile:
        print("当前默认是 onedir 模式，更适合这个项目的守护重启场景，也更省启动开销。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
