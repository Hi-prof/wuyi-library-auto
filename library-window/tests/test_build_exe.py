import os
import tempfile
import unittest
from pathlib import Path

from scripts.build_exe import (
    bump_project_version,
    bump_version_value,
    build_dist_path,
    build_output_path,
    build_pyinstaller_command,
    build_signtool_command,
    build_version_file_content,
    build_windows_file_version,
    copy_icon_to_output,
    prepare_output_support_files,
    reset_output_path,
    resolve_icon_path,
)
from unittest.mock import patch


class BuildExeTestCase(unittest.TestCase):
    def test_build_pyinstaller_command_collects_package_data_and_desktop_runtime(self) -> None:
        project_root = Path("C:/demo/project")
        expected_dist_path = build_dist_path(project_root)

        command = build_pyinstaller_command(
            project_root,
            name="demo-app",
            onefile=False,
            entry_script=project_root / "src" / "wuyi_seat_bot" / "cli.py",
            icon_path=None,
            include_package_data=True,
            include_desktop_runtime=True,
        )

        self.assertIn("--collect-data", command)
        self.assertIn("wuyi_seat_bot", command)
        self.assertIn("PIL", command)
        self.assertIn("pystray", command)
        self.assertIn("--onedir", command)
        self.assertIn("--distpath", command)
        self.assertIn(str(expected_dist_path), command)

    def test_build_pyinstaller_command_explicitly_adds_real_web_static_dir(self) -> None:
        project_root = Path("C:/demo/project")
        expected_source = project_root / "src" / "wuyi_seat_bot" / "web"
        expected_separator = ";" if os.name == "nt" else ":"

        command = build_pyinstaller_command(
            project_root,
            name="demo-app",
            onefile=False,
            entry_script=project_root / "src" / "wuyi_seat_bot" / "cli.py",
            icon_path=None,
            include_package_data=True,
            include_desktop_runtime=False,
        )

        add_data_index = command.index("--add-data")
        self.assertEqual(
            command[add_data_index + 1],
            f"{expected_source}{expected_separator}wuyi_seat_bot/web",
        )

    def test_build_pyinstaller_command_supports_onefile_mode(self) -> None:
        project_root = Path("C:/demo/project")

        command = build_pyinstaller_command(
            project_root,
            name="demo-app",
            onefile=True,
            entry_script=project_root / "src" / "wuyi_seat_bot" / "cli.py",
            icon_path=None,
            include_package_data=False,
            include_desktop_runtime=False,
            version_file_path=None,
        )

        self.assertIn("--onefile", command)
        self.assertNotIn("--onedir", command)

    def test_build_pyinstaller_command_includes_icon_when_provided(self) -> None:
        project_root = Path("C:/demo/project")
        icon_path = project_root / "assets" / "app-icon.ico"

        command = build_pyinstaller_command(
            project_root,
            name="demo-app",
            onefile=False,
            entry_script=project_root / "src" / "wuyi_seat_bot" / "cli.py",
            icon_path=icon_path,
            include_package_data=False,
            include_desktop_runtime=False,
            version_file_path=None,
        )

        self.assertIn("--icon", command)
        self.assertIn(str(icon_path), command)

    def test_build_pyinstaller_command_includes_version_file_when_provided(self) -> None:
        project_root = Path("C:/demo/project")
        version_file_path = project_root / "build" / "version-info.txt"

        command = build_pyinstaller_command(
            project_root,
            name="demo-app",
            onefile=False,
            entry_script=project_root / "src" / "wuyi_seat_bot" / "cli.py",
            icon_path=None,
            include_package_data=False,
            include_desktop_runtime=False,
            version_file_path=version_file_path,
        )

        self.assertIn("--version-file", command)
        self.assertIn(str(version_file_path), command)

    def test_build_pyinstaller_command_includes_specpath_when_provided(self) -> None:
        project_root = Path("C:/demo/project")
        spec_path = project_root / "build" / "pyinstaller-spec"

        command = build_pyinstaller_command(
            project_root,
            name="demo-app",
            onefile=False,
            entry_script=project_root / "src" / "wuyi_seat_bot" / "cli.py",
            icon_path=None,
            include_package_data=False,
            include_desktop_runtime=False,
            version_file_path=None,
            spec_path=spec_path,
        )

        self.assertIn("--specpath", command)
        self.assertIn(str(spec_path), command)

    def test_build_output_path_matches_packaging_mode(self) -> None:
        project_root = Path("C:/demo/project")

        self.assertEqual(
            build_output_path(project_root, name="demo-app", onefile=False),
            project_root.parent / "dist" / "exe" / "demo-app" / "demo-app.exe",
        )
        self.assertEqual(
            build_output_path(project_root, name="demo-app", onefile=True),
            project_root.parent / "dist" / "exe" / "demo-app.exe",
        )

    def test_build_dist_path_targets_exe_subdirectory(self) -> None:
        project_root = Path("C:/demo/project")

        self.assertEqual(build_dist_path(project_root), project_root.parent / "dist" / "exe")

    def test_resolve_icon_path_prefers_existing_default_icon(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            project_root = Path(tmp_dir)
            icon_path = project_root / "assets" / "app-icon.ico"
            icon_path.parent.mkdir(parents=True, exist_ok=True)
            icon_path.write_bytes(b"ico")

            resolved = resolve_icon_path(project_root, None)

        self.assertEqual(resolved, icon_path)

    def test_resolve_icon_path_uses_explicit_icon(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            icon_path = Path(tmp_dir) / "custom.ico"
            icon_path.write_bytes(b"ico")

            resolved = resolve_icon_path(Path("C:/demo/project"), str(icon_path))

        self.assertEqual(resolved, icon_path.resolve())

    def test_copy_icon_to_output_places_icon_next_to_onedir_exe(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            project_root = Path(tmp_dir)
            output_path = project_root / "dist" / "demo-app" / "demo-app.exe"
            output_path.parent.mkdir(parents=True, exist_ok=True)
            output_path.write_bytes(b"exe")
            icon_path = project_root / "assets" / "app-icon.ico"
            icon_path.parent.mkdir(parents=True, exist_ok=True)
            icon_path.write_bytes(b"ico")

            copied_icon_path = copy_icon_to_output(output_path=output_path, icon_path=icon_path)

            self.assertEqual(copied_icon_path, output_path.parent / "app-icon.ico")
            self.assertTrue(copied_icon_path.exists())
            self.assertEqual(copied_icon_path.read_bytes(), b"ico")

    def test_copy_icon_to_output_returns_none_when_icon_missing(self) -> None:
        copied_icon_path = copy_icon_to_output(
            output_path=Path("C:/demo/project/dist/demo-app/demo-app.exe"),
            icon_path=None,
        )

        self.assertIsNone(copied_icon_path)

    def test_reset_output_path_removes_onedir_directory(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            output_path = Path(tmp_dir) / "dist" / "exe" / "demo-app" / "demo-app.exe"
            output_path.parent.mkdir(parents=True, exist_ok=True)
            (output_path.parent / "stale.txt").write_text("old", encoding="utf-8")

            reset_output_path(output_path=output_path, onefile=False)

            self.assertFalse(output_path.parent.exists())

    def test_prepare_output_support_files_rebuilds_example_config_and_runtime(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            project_root = Path(tmp_dir) / "library-window"
            output_path = Path(tmp_dir) / "dist" / "exe" / "demo-app" / "demo-app.exe"
            output_path.parent.mkdir(parents=True, exist_ok=True)

            with patch("scripts.build_exe._write_example_config", return_value=output_path.parent / "config.json") as write_example_config:
                config_path = prepare_output_support_files(project_root, output_path=output_path)

            write_example_config.assert_called_once_with(project_root, output_path.parent / "config.json")
            self.assertEqual(config_path, output_path.parent / "config.json")
            self.assertTrue((output_path.parent / "runtime" / "logs").exists())

    def test_build_windows_file_version_pads_missing_segments(self) -> None:
        self.assertEqual(build_windows_file_version("3.0"), "3.0.0.0")
        self.assertEqual(build_windows_file_version("3.0.1"), "3.0.1.0")
        self.assertEqual(build_windows_file_version("3.0.1.9"), "3.0.1.9")

    def test_bump_version_value_increments_last_segment(self) -> None:
        self.assertEqual(bump_version_value("3.1"), "3.2")
        self.assertEqual(bump_version_value("3.1.9"), "3.1.10")

    def test_bump_project_version_updates_version_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            project_root = Path(tmp_dir)
            version_path = project_root / "src" / "wuyi_seat_bot" / "version.py"
            version_path.parent.mkdir(parents=True, exist_ok=True)
            version_path.write_text('__all__ = ["__version__"]\n\n__version__ = "3.1"\n', encoding="utf-8")

            bumped_version = bump_project_version(project_root)
            updated_content = version_path.read_text(encoding="utf-8")

        self.assertEqual(bumped_version, "3.2")
        self.assertIn('__version__ = "3.2"', updated_content)

    def test_build_version_file_content_contains_file_and_product_versions(self) -> None:
        content = build_version_file_content(name="demo-app", version="3.0")

        self.assertIn("filevers=(3, 0, 0, 0)", content)
        self.assertIn("prodvers=(3, 0, 0, 0)", content)
        self.assertIn("StringStruct('FileVersion', '3.0')", content)
        self.assertIn("StringStruct('ProductVersion', '3.0')", content)
        self.assertIn("StringStruct('OriginalFilename', 'demo-app.exe')", content)

    def test_build_signtool_command_supports_pfx_input(self) -> None:
        signtool_path = Path("C:/Windows Kits/signtool.exe")
        cert_file = Path("C:/secrets/codesign.pfx")
        file_path = Path("C:/demo/dist/demo-app.exe")
        command = build_signtool_command(
            signtool_path=signtool_path,
            file_path=file_path,
            timestamp_url="http://timestamp.digicert.com",
            cert_file=cert_file,
            cert_password="top-secret",
            cert_thumbprint=None,
        )

        self.assertEqual(command[0], str(signtool_path))
        self.assertIn("/f", command)
        self.assertIn(str(cert_file), command)
        self.assertIn("/p", command)
        self.assertIn("top-secret", command)
        self.assertIn("/tr", command)
        self.assertIn("http://timestamp.digicert.com", command)
        self.assertEqual(command[-1], str(file_path))

    def test_build_signtool_command_supports_thumbprint_input(self) -> None:
        command = build_signtool_command(
            signtool_path=Path("C:/Windows Kits/signtool.exe"),
            file_path=Path("C:/demo/dist/demo-app.exe"),
            timestamp_url="http://timestamp.digicert.com",
            cert_file=None,
            cert_password=None,
            cert_thumbprint="ABC123",
        )

        self.assertIn("/sha1", command)
        self.assertIn("ABC123", command)
        self.assertIn("/sm", command)
