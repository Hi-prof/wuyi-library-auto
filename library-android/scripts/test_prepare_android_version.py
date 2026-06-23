import importlib.util
import sys
import tempfile
import textwrap
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path


SCRIPT_PATH = Path(__file__).with_name("prepare_android_version.py")


def _load_module():
    spec = importlib.util.spec_from_file_location("prepare_android_version", SCRIPT_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


SHANGHAI_TIMEZONE = timezone(timedelta(hours=8))


class PrepareAndroidVersionTest(unittest.TestCase):
    def test_bump_version_file_increments_decimal_suffix_and_updates_build_marker(self):
        module = _load_module()
        with tempfile.TemporaryDirectory() as temp_dir:
            version_file = Path(temp_dir) / "version.properties"
            version_file.write_text(
                textwrap.dedent(
                    """
                    versionCode=202
                    versionName=3.0
                    buildMarker=2026-04-12-05
                    """
                ).strip()
                + "\n",
                encoding="utf-8",
            )

            result = module.bump_version_file(
                version_file,
                build_time=datetime(2026, 4, 13, 1, 30, 45, tzinfo=SHANGHAI_TIMEZONE),
            )

            self.assertEqual(result.version_code, 203)
            self.assertEqual(result.version_name, "3.1.0")
            self.assertEqual(result.build_marker, "2026-04-13-013045")
            self.assertEqual(
                version_file.read_text(encoding="utf-8"),
                "versionCode=203\nversionName=3.1.0\nbuildMarker=2026-04-13-013045\n",
            )

    def test_bump_version_name_treats_trailing_digits_as_decimal(self):
        module = _load_module()

        self.assertEqual(module.bump_version_name("3.0.9"), "3.0.10")
        self.assertEqual(module.bump_version_name("3.9"), "3.10.0")


if __name__ == "__main__":
    unittest.main()
