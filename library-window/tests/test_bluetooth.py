import unittest

from wuyi_seat_bot.bluetooth import (
    APPLE_IBEACON_COMPANY_ID,
    extract_ibeacon_minor,
    scan_for_matching_ibeacon_minor,
)


def build_ibeacon_payload(minor: int, *, major: int = 10008) -> bytes:
    uuid_bytes = bytes.fromhex("FDA50693A4E24FB1AFCFC6EB07647825")
    return (
        b"\x02\x15"
        + uuid_bytes
        + major.to_bytes(2, byteorder="big")
        + minor.to_bytes(2, byteorder="big")
        + b"\xC5"
    )


class _FakeAdvertisement:
    def __init__(self, manufacturer_data: dict[int, bytes]) -> None:
        self.manufacturer_data = manufacturer_data


class _MatchingScanner:
    @staticmethod
    async def discover(*, timeout: float, return_adv: bool) -> dict:
        return {
            "device-a": (object(), _FakeAdvertisement({APPLE_IBEACON_COMPANY_ID: build_ibeacon_payload(34173)})),
            "device-b": (object(), _FakeAdvertisement({APPLE_IBEACON_COMPANY_ID: build_ibeacon_payload(25146)})),
        }


class _NoMatchScanner:
    @staticmethod
    async def discover(*, timeout: float, return_adv: bool) -> dict:
        return {
            "device-a": (object(), _FakeAdvertisement({APPLE_IBEACON_COMPANY_ID: build_ibeacon_payload(55555)})),
            "device-b": (object(), _FakeAdvertisement({APPLE_IBEACON_COMPANY_ID: build_ibeacon_payload(44444)})),
        }


class BluetoothHelpersTestCase(unittest.TestCase):
    def test_extract_ibeacon_minor_reads_apple_payload(self) -> None:
        manufacturer_data = {APPLE_IBEACON_COMPANY_ID: build_ibeacon_payload(34173)}

        self.assertEqual(extract_ibeacon_minor(manufacturer_data), 34173)

    def test_extract_ibeacon_minor_ignores_non_ibeacon_payload(self) -> None:
        manufacturer_data = {APPLE_IBEACON_COMPANY_ID: b"\x01\x02\x03"}

        self.assertIsNone(extract_ibeacon_minor(manufacturer_data))

    def test_scan_for_matching_ibeacon_minor_returns_first_expected_match(self) -> None:
        result = scan_for_matching_ibeacon_minor(
            (34173, 34174),
            timeout_seconds=1,
            scanner_class=_MatchingScanner,
        )

        self.assertEqual(result.matched_minor, 34173)
        self.assertEqual(result.seen_minors, (34173, 25146))

    def test_scan_for_matching_ibeacon_minor_reports_seen_minors_when_unmatched(self) -> None:
        result = scan_for_matching_ibeacon_minor(
            (34173, 34174),
            timeout_seconds=1,
            scanner_class=_NoMatchScanner,
        )

        self.assertIsNone(result.matched_minor)
        self.assertEqual(result.seen_minors, (55555, 44444))
