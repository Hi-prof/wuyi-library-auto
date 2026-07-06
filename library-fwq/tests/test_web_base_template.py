"""Tests for base template and navigation component."""


def test_base_template_exists():
    """Verify base.html template exists."""
    from pathlib import Path
    from prevent_auto.settings import load_settings
    settings = load_settings()
    template_path = settings.package_root / "src" / "prevent_auto" / "web" / "templates" / "base.html"
    assert template_path.exists()


def test_nav_component_exists():
    """Verify nav.html component exists."""
    from pathlib import Path
    from prevent_auto.settings import load_settings
    settings = load_settings()
    template_path = settings.package_root / "src" / "prevent_auto" / "web" / "templates" / "components" / "nav.html"
    assert template_path.exists()
