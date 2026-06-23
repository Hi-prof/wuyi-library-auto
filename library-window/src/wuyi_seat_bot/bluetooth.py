from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Iterable, Sequence

APPLE_IBEACON_COMPANY_ID = 0x004C
IBEACON_PREFIX = b"\x02\x15"


@dataclass(frozen=True)
class BluetoothScanResult:
    matched_minor: int | None
    seen_minors: tuple[int, ...]


def scan_for_matching_ibeacon_minor(
    expected_minors: Iterable[int],
    *,
    timeout_seconds: float = 8.0,
    scanner_class=None,
) -> BluetoothScanResult:
    normalized_expected = tuple(dict.fromkeys(int(minor) for minor in expected_minors))
    if not normalized_expected:
        raise ValueError("至少需要提供一个蓝牙设备 minor")

    scanner = scanner_class or _load_bleak_scanner()
    seen_minors = _run_discovery(scanner, timeout_seconds)
    expected_set = set(normalized_expected)
    matched_minor = next((minor for minor in seen_minors if minor in expected_set), None)
    return BluetoothScanResult(matched_minor=matched_minor, seen_minors=seen_minors)


def extract_ibeacon_minor(manufacturer_data: dict[int, bytes | bytearray]) -> int | None:
    if not isinstance(manufacturer_data, dict):
        return None

    # 学校前端最终也是按 iBeacon 的 minor 做匹配，这里只抽取最关键的字段，
    # 既能贴近真实逻辑，也能避免把平台差异更大的 UUID/major 绑定得太死。
    payload = manufacturer_data.get(APPLE_IBEACON_COMPANY_ID)
    if payload is None:
        return None

    raw_payload = bytes(payload)
    if len(raw_payload) < 22 or raw_payload[:2] != IBEACON_PREFIX:
        return None
    return int.from_bytes(raw_payload[20:22], byteorder="big")


async def _discover_ibeacon_minors(scanner_class, timeout_seconds: float) -> tuple[int, ...]:
    discovered = await scanner_class.discover(timeout=timeout_seconds, return_adv=True)
    if not isinstance(discovered, dict):
        raise RuntimeError("当前蓝牙扫描器未返回广告数据，无法识别 iBeacon")

    seen_minors: list[int] = []
    seen_set: set[int] = set()
    for _, advertisement in discovered.values():
        manufacturer_data = getattr(advertisement, "manufacturer_data", None)
        minor = extract_ibeacon_minor(manufacturer_data or {})
        if minor is None or minor in seen_set:
            continue
        seen_set.add(minor)
        seen_minors.append(minor)
    return tuple(seen_minors)


def _run_discovery(scanner_class, timeout_seconds: float) -> tuple[int, ...]:
    try:
        running_loop = asyncio.get_running_loop()
    except RuntimeError:
        running_loop = None

    if running_loop is not None and running_loop.is_running():
        raise RuntimeError("当前存在运行中的事件循环，无法直接执行蓝牙扫描，请改用命令行触发签到")
    return asyncio.run(_discover_ibeacon_minors(scanner_class, timeout_seconds))


def _load_bleak_scanner():
    try:
        from bleak import BleakScanner
    except ImportError as exc:
        raise RuntimeError("未安装蓝牙扫描依赖 bleak，请先重新安装项目依赖") from exc
    return BleakScanner
