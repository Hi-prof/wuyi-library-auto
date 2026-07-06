"""Tests for base template rendering."""
from pathlib import Path
from prevent_auto.settings import load_settings


def test_base_template_syntax():
    """Verify base.html has valid Jinja2 syntax."""
    from jinja2 import Environment, FileSystemLoader, TemplateSyntaxError

    settings = load_settings()
    template_dir = settings.package_root / "src" / "prevent_auto" / "web" / "templates"

    env = Environment(loader=FileSystemLoader(str(template_dir)))

    try:
        template = env.get_template("base.html")
        assert template is not None
    except TemplateSyntaxError as e:
        raise AssertionError(f"Template syntax error: {e}")


def test_legacy_base_navigation_assets():
    """Verify base.html includes the legacy mobile navigation hooks."""
    from jinja2 import Environment, FileSystemLoader, TemplateSyntaxError

    settings = load_settings()
    template_dir = settings.package_root / "src" / "prevent_auto" / "web" / "templates"

    env = Environment(loader=FileSystemLoader(str(template_dir)))

    try:
        template = env.get_template("base.html")
        assert template is not None
    except TemplateSyntaxError as e:
        raise AssertionError(f"Template syntax error: {e}")


def test_base_template_includes_legacy_stylesheets():
    """Verify base.html references the legacy stylesheet pair."""
    settings = load_settings()
    template_path = settings.package_root / "src" / "prevent_auto" / "web" / "templates" / "base.html"
    content = template_path.read_text(encoding="utf-8")
    assert "styles.css" in content
    assert "mobile.css" in content


def test_base_template_uses_middle_dot_title_separator():
    """Verify the page title separator is not mojibake."""
    settings = load_settings()
    template_path = settings.package_root / "src" / "prevent_auto" / "web" / "templates" / "base.html"
    content = template_path.read_text(encoding="utf-8")
    assert "{{ page_title }} · Prevent Auto" in content


def test_base_template_uses_side_nav_shell():
    """Verify base.html keeps the original side navigation layout."""
    settings = load_settings()
    template_path = settings.package_root / "src" / "prevent_auto" / "web" / "templates" / "base.html"
    content = template_path.read_text(encoding="utf-8")
    assert "page-shell" in content
    assert "side-nav" in content
    assert "data-nav-toggle" in content
