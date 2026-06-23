"""批量测试图书馆账号能否登录。

用法示例：
    python check_accounts.py --input accounts.txt
    python check_accounts.py --ids 20231121130 20231121131 --workers 4

约定：
- 每个账号的登录名和密码都是学号本身。
- 登录接口、参数与 ``library-window`` 中的 ``_login_with_credentials`` 保持一致，
  但本脚本不依赖该包，只用 Python 标准库，方便单独运行。
- 结果会写入 ``--output-dir``（默认 ``results/<时间戳>/``），包含：
    success.txt    可正常登录的学号（一行一个）
    failed.csv     登录失败的学号及原因
    summary.json   汇总信息
"""
from __future__ import annotations

import argparse
import csv
import http.cookiejar
import json
import sys
import time
import urllib.error
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import urljoin, urlparse, urlunparse
from urllib.request import HTTPCookieProcessor, Request, build_opener


DEFAULT_LOGIN_URL = "https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list"
LOGIN_APPLICATION_ID = "lab4"
LOGIN_METADATA_PATH = "/User/Index/login?LAB_JSON=1"
LOGIN_REQUEST_PATH = "/api/1/login"
LANGUAGE_COOKIE = "web_language=zh-CN"
DEFAULT_TIMEOUT = 20.0


@dataclass
class LoginResult:
    student_id: str
    success: bool
    message: str
    user_name: str = ""
    elapsed_ms: int = 0


def _resolve_origin(url: str) -> str:
    parsed = urlparse(url)
    if not parsed.scheme or not parsed.netloc:
        raise ValueError(f"无效的登录地址：{url}")
    return urlunparse((parsed.scheme, parsed.netloc, "", "", "", ""))


def _build_request_headers(origin: str, *, extra: dict[str, str] | None = None) -> dict[str, str]:
    headers = {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
        ),
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "zh-CN,zh;q=0.9",
        "Cookie": LANGUAGE_COOKIE,
        "Referer": f"{origin}/",
    }
    if extra:
        headers.update(extra)
    return headers


def _open_json(opener, request: Request, *, timeout: float) -> Any:
    try:
        with opener.open(request, timeout=timeout) as response:
            body = response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace").strip()
        try:
            payload: Any = json.loads(raw) if raw else ""
        except json.JSONDecodeError:
            payload = raw
        raise LoginError(_extract_error_message(payload, f"HTTP {exc.code}")) from exc
    except urllib.error.URLError as exc:
        raise LoginError(f"网络错误：{exc.reason}") from exc
    except TimeoutError as exc:
        raise LoginError("请求超时") from exc

    try:
        return json.loads(body)
    except json.JSONDecodeError as exc:
        raise LoginError(f"返回内容不是 JSON：{body[:200]}") from exc


def _extract_error_message(payload: Any, default_message: str) -> str:
    if isinstance(payload, dict):
        for key in ("message", "msg", "error", "code"):
            value = str(payload.get(key, "")).strip()
            if value:
                return value
    elif isinstance(payload, str) and payload.strip():
        return payload.strip()
    return default_message


class LoginError(Exception):
    """登录失败时抛出，携带可读的中文原因。"""


def try_login(student_id: str, *, login_url: str, timeout: float) -> tuple[str, str]:
    """成功时返回 (用户姓名, 提示信息)，失败时抛 LoginError。"""

    origin = _resolve_origin(login_url)
    cookie_jar = http.cookiejar.CookieJar()
    opener = build_opener(HTTPCookieProcessor(cookie_jar))
    installation_id = str(uuid.uuid4())

    meta = _open_json(
        opener,
        Request(
            url=urljoin(origin, LOGIN_METADATA_PATH),
            headers=_build_request_headers(origin),
            method="GET",
        ),
        timeout=timeout,
    )
    if not isinstance(meta, dict):
        raise LoginError("登录页返回格式异常")
    raw_data = meta.get("content", {}).get("data", {})
    org_id = str(
        meta.get("content", {})
        .get("itemHeader", {})
        .get("defaultData", {})
        .get("custom_value", "")
    ).strip()
    if not isinstance(raw_data, dict) or not raw_data.get("code") or not raw_data.get("str") or not org_id:
        raise LoginError("登录页未返回完整认证参数，请稍后再试")

    payload = {
        "login_name": student_id,
        "password": student_id,
        "ui_type": "com.Raw",
        "code": raw_data["code"],
        "str": raw_data["str"],
        "org_id": org_id,
        "_ApplicationId": LOGIN_APPLICATION_ID,
        "_JavaScriptKey": LOGIN_APPLICATION_ID,
        "_ClientVersion": "js_xxx",
        "_InstallationId": installation_id,
        "_SessionToken": "fake",
    }
    current_user = _open_json(
        opener,
        Request(
            url=urljoin(origin, LOGIN_REQUEST_PATH),
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers=_build_request_headers(
                origin,
                extra={"Content-Type": "text/plain", "Origin": origin},
            ),
            method="POST",
        ),
        timeout=timeout,
    )
    if not isinstance(current_user, dict) or not str(current_user.get("id", "")).strip():
        raise LoginError(_extract_error_message(current_user, "登录失败，请检查学号或密码"))

    name = str(current_user.get("name") or current_user.get("nickname") or "").strip()
    if not name:
        raise LoginError("登录返回缺少姓名（可能账号未完成读者绑定）")
    return name, "登录成功"


def load_student_ids(path: Path) -> list[str]:
    ids: list[str] = []
    seen: set[str] = set()
    with path.open("r", encoding="utf-8-sig") as handle:
        for raw_line in handle:
            line = raw_line.strip()
            if not line or line.startswith("#"):
                continue
            # 允许 "学号 备注" 这样的格式，只取首列
            student_id = line.split()[0].strip()
            if student_id and student_id not in seen:
                seen.add(student_id)
                ids.append(student_id)
    return ids


def write_results(results: list[LoginResult], output_dir: Path) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    success = [r for r in results if r.success]
    failed = [r for r in results if not r.success]

    (output_dir / "success.txt").write_text(
        "\n".join(r.student_id for r in success) + ("\n" if success else ""),
        encoding="utf-8",
    )

    with (output_dir / "failed.csv").open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["student_id", "message", "elapsed_ms"])
        for item in failed:
            writer.writerow([item.student_id, item.message, item.elapsed_ms])

    summary = {
        "total": len(results),
        "success": len(success),
        "failed": len(failed),
        "results": [
            {
                "student_id": r.student_id,
                "success": r.success,
                "message": r.message,
                "user_name": r.user_name,
                "elapsed_ms": r.elapsed_ms,
            }
            for r in results
        ],
    }
    (output_dir / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def run_one(student_id: str, *, login_url: str, timeout: float) -> LoginResult:
    started = time.perf_counter()
    try:
        name, message = try_login(student_id, login_url=login_url, timeout=timeout)
        return LoginResult(
            student_id=student_id,
            success=True,
            message=message,
            user_name=name,
            elapsed_ms=int((time.perf_counter() - started) * 1000),
        )
    except LoginError as exc:
        return LoginResult(
            student_id=student_id,
            success=False,
            message=str(exc),
            elapsed_ms=int((time.perf_counter() - started) * 1000),
        )
    except Exception as exc:  # noqa: BLE001
        return LoginResult(
            student_id=student_id,
            success=False,
            message=f"未知错误：{exc!r}",
            elapsed_ms=int((time.perf_counter() - started) * 1000),
        )


def run_batch(
    ids: Iterable[str],
    *,
    login_url: str,
    timeout: float,
    workers: int,
    delay: float,
) -> list[LoginResult]:
    ids = list(ids)
    total = len(ids)
    results: list[LoginResult] = []

    if workers <= 1:
        for index, sid in enumerate(ids, start=1):
            result = run_one(sid, login_url=login_url, timeout=timeout)
            results.append(result)
            _print_progress(index, total, result)
            if delay > 0 and index < total:
                time.sleep(delay)
        return results

    # 并发模式下，delay 仅用于线程内 jitter，避免发起瞬时风暴
    with ThreadPoolExecutor(max_workers=workers) as pool:
        future_to_id = {
            pool.submit(run_one, sid, login_url=login_url, timeout=timeout): sid
            for sid in ids
        }
        for index, future in enumerate(as_completed(future_to_id), start=1):
            result = future.result()
            results.append(result)
            _print_progress(index, total, result)
    # 保持输出顺序与输入一致
    order = {sid: i for i, sid in enumerate(ids)}
    results.sort(key=lambda r: order.get(r.student_id, 0))
    return results


def _print_progress(index: int, total: int, result: LoginResult) -> None:
    tag = "OK " if result.success else "FAIL"
    name = f" {result.user_name}" if result.user_name else ""
    print(f"[{index:>4}/{total}] {tag} {result.student_id}{name} - {result.message}", flush=True)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="批量测试图书馆账号能否登录（账号密码均为学号）")
    src = parser.add_mutually_exclusive_group(required=True)
    src.add_argument("--input", "-i", type=Path, help="包含学号的文本文件（一行一个，# 开头为注释）")
    src.add_argument("--ids", nargs="+", help="直接在命令行传入若干学号")
    parser.add_argument(
        "--login-url",
        default=DEFAULT_LOGIN_URL,
        help=f"登录页 URL，默认 {DEFAULT_LOGIN_URL}",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=None,
        help="结果输出目录，默认 results/<时间戳>/",
    )
    parser.add_argument("--workers", type=int, default=1, help="并发线程数，默认 1（顺序执行）")
    parser.add_argument(
        "--delay",
        type=float,
        default=0.5,
        help="顺序模式下每次登录之间的等待秒数，默认 0.5",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=DEFAULT_TIMEOUT,
        help=f"单次请求超时秒数，默认 {DEFAULT_TIMEOUT}",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="只解析输入并打印学号列表，不发起请求",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)

    if args.input is not None:
        if not args.input.exists():
            print(f"未找到输入文件：{args.input}", file=sys.stderr)
            return 2
        ids = load_student_ids(args.input)
    else:
        ids = []
        seen: set[str] = set()
        for raw in args.ids or []:
            sid = raw.strip()
            if sid and sid not in seen:
                seen.add(sid)
                ids.append(sid)

    if not ids:
        print("没有可测试的学号。", file=sys.stderr)
        return 2

    if args.dry_run:
        print(f"将测试 {len(ids)} 个学号：")
        for sid in ids:
            print(f"  - {sid}")
        return 0

    output_dir = args.output_dir or (
        Path(__file__).resolve().parent
        / "results"
        / datetime.now().strftime("%Y%m%d-%H%M%S")
    )
    print(f"开始批量登录测试：共 {len(ids)} 个学号，结果将写入 {output_dir}")

    workers = max(1, int(args.workers))
    results = run_batch(
        ids,
        login_url=args.login_url,
        timeout=float(args.timeout),
        workers=workers,
        delay=max(0.0, float(args.delay)),
    )

    write_results(results, output_dir)

    success_count = sum(1 for r in results if r.success)
    print()
    print(f"完成：成功 {success_count} / 失败 {len(results) - success_count} / 共 {len(results)}")
    print(f"详细结果：{output_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
