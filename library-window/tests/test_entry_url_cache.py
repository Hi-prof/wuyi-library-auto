import json
import tempfile
import unittest
from pathlib import Path

from wuyi_seat_bot.entry_url_cache import (
    build_entry_url_cache_path,
    load_resolved_entry_urls,
    save_resolved_entry_url,
    save_resolved_entry_urls,
)


class EntryUrlCacheTestCase(unittest.TestCase):
    def test_save_and_load_resolved_entry_urls(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")

            save_resolved_entry_urls(
                config_path,
                "主号",
                {
                    "https://example.com/list": "https://example.com/searchSeats?a=1",
                },
            )

            self.assertEqual(
                load_resolved_entry_urls(config_path, "主号"),
                {"https://example.com/list": "https://example.com/searchSeats?a=1"},
            )

    def test_save_resolved_entry_url_merges_existing_entries(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")

            save_resolved_entry_url(
                config_path,
                "主号",
                "https://example.com/list-a",
                "https://example.com/searchSeats?a=1",
            )
            save_resolved_entry_url(
                config_path,
                "主号",
                "https://example.com/list-b",
                "https://example.com/searchSeats?b=1",
            )

            cache_path = build_entry_url_cache_path(config_path)
            payload = json.loads(cache_path.read_text(encoding="utf-8"))

        self.assertEqual(
            payload["accounts"]["主号"],
            {
                "https://example.com/list-a": "https://example.com/searchSeats?a=1",
                "https://example.com/list-b": "https://example.com/searchSeats?b=1",
            },
        )
