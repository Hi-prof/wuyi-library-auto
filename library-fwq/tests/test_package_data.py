"""Tests for Python package static asset inclusion."""

from __future__ import annotations

import tomllib

from prevent_auto.settings import load_settings


def test_package_data_includes_root_static_stylesheets():
    """Verify static CSS referenced by base.html is included in wheels."""
    settings = load_settings()
    pyproject_path = settings.package_root / "pyproject.toml"
    data = tomllib.loads(pyproject_path.read_text(encoding="utf-8"))

    package_data = data["tool"]["setuptools"]["package-data"]["prevent_auto"]

    assert "web/static/*.css" in package_data
