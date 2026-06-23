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


def test_nav_component_syntax():
    """Verify nav.html has valid Jinja2 syntax."""
    from jinja2 import Environment, FileSystemLoader, TemplateSyntaxError

    settings = load_settings()
    template_dir = settings.package_root / "src" / "prevent_auto" / "web" / "templates"

    env = Environment(loader=FileSystemLoader(str(template_dir)))

    try:
        template = env.get_template("components/nav.html")
        assert template is not None
    except TemplateSyntaxError as e:
        raise AssertionError(f"Template syntax error: {e}")


def test_base_template_includes_alpine():
    """Verify base.html includes Alpine.js CDN."""
    settings = load_settings()
    template_path = settings.package_root / "src" / "prevent_auto" / "web" / "templates" / "base.html"
    content = template_path.read_text(encoding="utf-8")
    assert "alpinejs" in content.lower()


def test_base_template_includes_tailwind():
    """Verify base.html references main.css (Tailwind output)."""
    settings = load_settings()
    template_path = settings.package_root / "src" / "prevent_auto" / "web" / "templates" / "base.html"
    content = template_path.read_text(encoding="utf-8")
    assert "main.css" in content
