# 撤回 spec account-pool-tri-sync 11.4 中的 ConnectivityGate 守卫；本地执行不再依赖服务端可达性
import argparse
import ctypes
import subprocess
import sys
from pathlib import Path

DEFAULT_CONFIG_NAME = "config.json"
TRAY_LAUNCH_FLAG = "--tray-launch"
ENABLE_STABILITY_ENHANCEMENT_FLAG = "--enable-stability-enhancement"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="武夷学院自习室座位预约自动化工具")
    parser.add_argument(
        "--config",
        default=DEFAULT_CONFIG_NAME,
        help="配置文件路径，默认为当前目录下的 config.json",
    )

    subparsers = parser.add_subparsers(dest="command", required=True)

    init_parser = subparsers.add_parser("init-config", help="生成示例配置文件")
    init_parser.add_argument("--force", action="store_true", help="覆盖已存在的配置文件")

    save_login_parser = subparsers.add_parser("save-login", help="使用账号密码登录并保存登录态")
    save_login_parser.add_argument("--account", help="指定账号名称；多账号配置时建议显式传入")

    reserve_parser = subparsers.add_parser("reserve", help="执行自动预约")
    reserve_parser.add_argument("--account", help="指定账号名称；不传时使用默认账号")

    checkin_parser = subparsers.add_parser("checkin", help="执行自动签到")
    checkin_parser.add_argument("--account", help="指定账号名称；不传时使用默认账号")

    checkout_parser = subparsers.add_parser("checkout", help="执行自动签退")
    checkout_parser.add_argument("--account", help="指定账号名称；不传时使用默认账号")

    inspect_parser = subparsers.add_parser("inspect-status", help="只检查接口可预约状态，不执行预约、签到或签退")
    inspect_parser.add_argument("--url", help="只检查指定网址，不使用配置里的 seat_urls")
    inspect_parser.add_argument("--account", help="指定账号名称；不传时使用默认账号")

    web_parser = subparsers.add_parser("web", help="启动本地网页选座界面")
    web_parser.add_argument("--host", default="127.0.0.1", help="监听地址，默认 127.0.0.1")
    web_parser.add_argument("--port", type=int, default=8765, help="监听端口，默认 8765")
    web_parser.add_argument("--no-open", action="store_true", help="启动后不自动打开浏览器")
    web_parser.add_argument("--account", help="网页初始默认账号；进入网页后仍可切换")
    web_parser.add_argument(TRAY_LAUNCH_FLAG, action="store_true", help=argparse.SUPPRESS)
    web_parser.add_argument(ENABLE_STABILITY_ENHANCEMENT_FLAG, action="store_true", help=argparse.SUPPRESS)
    web_parser.add_argument(
        "--no-guard",
        action="store_true",
        help="直接运行工作进程，不启用守护监控；仅建议排查问题时使用",
    )

    worker_parser = subparsers.add_parser("_web-worker", help=argparse.SUPPRESS)
    worker_parser.add_argument("--host", default="127.0.0.1")
    worker_parser.add_argument("--port", type=int, default=8765)
    worker_parser.add_argument("--account")

    settings_window_parser = subparsers.add_parser("_settings-window", help=argparse.SUPPRESS)
    settings_window_parser.add_argument("--parent-pid", type=int)

    subparsers.add_parser("status", help="查看受守护服务的运行状态")

    return parser


def main(argv: list[str] | None = None) -> int:
    _ensure_utf8_console()
    prepared_argv, should_relaunch_tray = _prepare_argv(argv)
    if should_relaunch_tray:
        return _relaunch_detached_tray_process(prepared_argv)

    parser = build_parser()
    args = parser.parse_args(prepared_argv)
    config_path = _resolve_config_path(args.config, argv=prepared_argv)
    account_name = getattr(args, "account", None)
    tray_mode = bool(getattr(args, "tray_launch", False))
    auto_enable_stability_enhancement = bool(getattr(args, "enable_stability_enhancement", False))

    try:
        _bootstrap_missing_web_config(args.command, config_path)

        if args.command == "init-config":
            from wuyi_seat_bot.config import write_example_config

            write_example_config(config_path, overwrite=args.force)
            print(f"已生成示例配置：{config_path}")
            return 0

        if args.command == "web":
            if args.no_guard:
                from wuyi_seat_bot.web_server import start_web_server

                exit_code = start_web_server(
                    config_path,
                    host=args.host,
                    port=args.port,
                    open_browser=not args.no_open,
                    account_name=account_name,
                )
            else:
                from wuyi_seat_bot.service_manager import start_guarded_web_server

                exit_code = start_guarded_web_server(
                    config_path,
                    host=args.host,
                    port=args.port,
                    open_browser=not args.no_open,
                    account_name=account_name,
                    tray_mode=tray_mode,
                    auto_enable_stability_enhancement=auto_enable_stability_enhancement,
                )
            return _finalize_command_exit(exit_code, auto_launch_web=tray_mode)

        if args.command == "_web-worker":
            from wuyi_seat_bot.service_manager import run_web_worker

            exit_code = run_web_worker(
                config_path,
                host=args.host,
                port=args.port,
                account_name=account_name,
            )
            return _finalize_command_exit(exit_code, auto_launch_web=tray_mode)

        if args.command == "_settings-window":
            from wuyi_seat_bot.desktop_settings.app import run_settings_window

            exit_code = run_settings_window(
                config_path,
                parent_pid=getattr(args, "parent_pid", None),
            )
            return _finalize_command_exit(exit_code, auto_launch_web=tray_mode)

        if args.command == "status":
            from wuyi_seat_bot.service_manager import get_service_status_report

            healthy, report = get_service_status_report(config_path)
            print(report)
            exit_code = 0 if healthy else 1
            return _finalize_command_exit(exit_code, auto_launch_web=tray_mode)

        from wuyi_seat_bot.api_seat_automation import ApiSeatAutomation
        from wuyi_seat_bot.config import load_config
        from wuyi_seat_bot.models import ActionType
        from wuyi_seat_bot.workflow import SeatWorkflow

        config = load_config(config_path, account_name=account_name)
        automation = ApiSeatAutomation(
            config=config,
            config_path=config_path,
        )

        if args.command == "save-login":
            state_path = automation.save_login_state()
            print(f"登录态已保存到：{state_path}")
            return 0

        if args.command == "inspect-status":
            target_urls = (args.url,) if args.url else config.seat_urls
            for target_url in target_urls:
                print(automation.inspect_seat_status(target_url))
            return 0

        action = {
            "reserve": ActionType.RESERVE,
            "checkin": ActionType.CHECKIN,
            "checkout": ActionType.CHECKOUT,
        }[args.command]
        result = SeatWorkflow(config, automation).run(action)
        print(result.message)
        return _finalize_command_exit(0 if result.success else 1, auto_launch_web=tray_mode)
    except Exception as exc:  # noqa: BLE001
        print(f"执行失败：{exc}", file=sys.stderr)
        return _finalize_command_exit(1, auto_launch_web=tray_mode)


def _bootstrap_missing_web_config(command: str, config_path: Path) -> None:
    if command != "web" or config_path.exists():
        return

    from wuyi_seat_bot.config import write_example_config

    # 只在网页入口自动补示例配置，避免普通命令悄悄生成占位账号后继续执行。
    config_path.parent.mkdir(parents=True, exist_ok=True)
    write_example_config(config_path, overwrite=False)
    print(f"未找到配置文件，已自动生成示例配置：{config_path}")
    print("请先在网页里补全账号和密码，再使用预约、签到、签退功能。")


def _prepare_argv(argv: list[str] | None) -> tuple[list[str], bool]:
    prepared = list(sys.argv[1:] if argv is None else argv)
    should_relaunch_tray = False
    if not prepared and getattr(sys, "frozen", False):
        # 双击 exe 时先拉起一个脱离控制台的托盘进程，再让当前控制台引导进程立即退出。
        prepared = ["web", TRAY_LAUNCH_FLAG]
        should_relaunch_tray = True
    return prepared, should_relaunch_tray


def _ensure_utf8_console() -> None:
    if sys.platform == "win32":
        try:
            kernel32 = ctypes.windll.kernel32
            kernel32.SetConsoleCP(65001)
            kernel32.SetConsoleOutputCP(65001)
        except Exception:  # noqa: BLE001
            pass

    for stream_name in ("stdout", "stderr"):
        stream = getattr(sys, stream_name, None)
        if stream is not None and hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")


def _relaunch_detached_tray_process(argv: list[str]) -> int:
    executable_path = _get_runtime_executable_path()
    command = [sys.executable, *argv]
    creationflags = (
        getattr(subprocess, "DETACHED_PROCESS", 0)
        | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
        | getattr(subprocess, "CREATE_NO_WINDOW", 0)
    )
    startupinfo = None
    if hasattr(subprocess, "STARTUPINFO"):
        startupinfo = subprocess.STARTUPINFO()
        startupinfo.dwFlags |= getattr(subprocess, "STARTF_USESHOWWINDOW", 0)
        startupinfo.wShowWindow = 0

    try:
        subprocess.Popen(
            command,
            cwd=str(executable_path.parent) if executable_path is not None else None,
            creationflags=creationflags,
            startupinfo=startupinfo,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        return 0
    except Exception as exc:  # noqa: BLE001
        print(f"执行失败：后台启动失败：{exc}", file=sys.stderr)
        _show_auto_launch_error()
        return 1


def _resolve_config_path(config_value: str, *, argv: list[str]) -> Path:
    requested_path = Path(config_value)
    if _has_explicit_config_argument(argv):
        return requested_path
    if requested_path.is_absolute() or requested_path.name != DEFAULT_CONFIG_NAME:
        return requested_path
    return _find_default_config_path(requested_path.name)


def _has_explicit_config_argument(argv: list[str]) -> bool:
    return "--config" in argv


def _find_default_config_path(config_name: str) -> Path:
    candidates: list[Path] = []
    candidates.extend(_collect_config_search_roots(config_name))
    for candidate in candidates:
        if candidate.exists():
            return candidate
    return candidates[0] if candidates else Path(config_name)


def _collect_config_search_roots(config_name: str) -> list[Path]:
    search_roots: list[Path] = [Path.cwd()]
    executable_path = _get_runtime_executable_path()
    if executable_path is not None:
        search_roots.extend(
            [
                executable_path.parent,
                executable_path.parent.parent,
                executable_path.parent.parent.parent,
            ]
        )
    script_path = Path(__file__).resolve()
    search_roots.extend([script_path.parent, script_path.parent.parent, script_path.parent.parent.parent])

    candidates: list[Path] = []
    seen: set[Path] = set()
    for root in search_roots:
        candidate = (root / config_name).resolve()
        if candidate in seen:
            continue
        seen.add(candidate)
        candidates.append(candidate)
    return candidates


def _get_runtime_executable_path() -> Path | None:
    executable = getattr(sys, "executable", None)
    if not executable:
        return None
    return Path(executable).resolve()


def _finalize_command_exit(exit_code: int, *, auto_launch_web: bool) -> int:
    if auto_launch_web and exit_code != 0:
        _show_auto_launch_error()
    return exit_code


def _show_auto_launch_error() -> None:
    try:
        import ctypes

        ctypes.windll.user32.MessageBoxW(None, "启动失败，请从托盘或日志里查看详情。", "启动失败", 0x10)
    except Exception:  # noqa: BLE001
        return


if __name__ == "__main__":
    raise SystemExit(main())
