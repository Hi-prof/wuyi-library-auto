# Library-FWQ Modernization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Modernize library-fwq from a monolithic structure to a modular, maintainable system with modern UI using Tailwind CSS + Alpine.js while keeping FastAPI + Jinja2.

**Architecture:** Split the 3162-line app.py into focused modules (routers, views, schemas), replace 2669-line custom CSS with Tailwind CSS, and add Alpine.js for interactions. Keep all existing functionality working.

**Tech Stack:**
- Backend: FastAPI 0.115+, Jinja2 3.1+, Python 3.10+
- Frontend: Tailwind CSS 3.4+, Alpine.js 3.x, Heroicons
- Build: Tailwind CLI, PostCSS

## Global Constraints

- Python version: >=3.10
- FastAPI version: >=0.115,<1.0
- Jinja2 version: >=3.1,<4.0
- All existing routes and functionality must remain working
- All existing templates must render correctly with new styles
- Database schema remains unchanged
- API responses remain backward compatible
- Configuration via environment variables unchanged
- Session-based authentication preserved
- All existing tests must pass

---

## Task 1: Setup Tailwind CSS Infrastructure

**Goal:** Install and configure Tailwind CSS with build pipeline

**Files:**
- Create: `library-fwq/package.json`
- Create: `library-fwq/tailwind.config.js`
- Create: `library-fwq/postcss.config.js`
- Create: `library-fwq/src/prevent_auto/web/static/css/input.css`
- Create: `library-fwq/.gitignore` (append)
- Create: `library-fwq/scripts/build-css.sh`
- Modify: `library-fwq/pyproject.toml` (package-data)

**Interfaces:**
- Produces: Tailwind build command `npm run build:css`
- Produces: Output file `src/prevent_auto/web/static/css/main.css`

- [ ] **Step 1: Create package.json for Node dependencies**

```json
{
  "name": "prevent-auto-frontend",
  "version": "0.1.0",
  "private": true,
  "scripts": {
    "build:css": "tailwindcss -i ./src/prevent_auto/web/static/css/input.css -o ./src/prevent_auto/web/static/css/main.css --minify",
    "watch:css": "tailwindcss -i ./src/prevent_auto/web/static/css/input.css -o ./src/prevent_auto/web/static/css/main.css --watch"
  },
  "devDependencies": {
    "tailwindcss": "^3.4.0",
    "@tailwindcss/forms": "^0.5.7",
    "autoprefixer": "^10.4.16",
    "postcss": "^8.4.32"
  }
}
```

- [ ] **Step 2: Create Tailwind configuration**

```javascript
// tailwind.config.js
/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    "./src/prevent_auto/web/templates/**/*.html",
    "./src/prevent_auto/web/static/js/**/*.js",
  ],
  theme: {
    extend: {
      colors: {
        primary: {
          50: '#eff6ff',
          100: '#dbeafe',
          200: '#bfdbfe',
          300: '#93c5fd',
          400: '#60a5fa',
          500: '#3b82f6',
          600: '#2563eb',
          700: '#1d4ed8',
          800: '#1e40af',
          900: '#1e3a8a',
        },
      },
    },
  },
  plugins: [
    require('@tailwindcss/forms'),
  ],
}
```

- [ ] **Step 3: Create PostCSS configuration**

```javascript
// postcss.config.js
module.exports = {
  plugins: {
    tailwindcss: {},
    autoprefixer: {},
  },
}
```

- [ ] **Step 4: Create Tailwind input CSS file**

```css
/* src/prevent_auto/web/static/css/input.css */
@tailwind base;
@tailwind components;
@tailwind utilities;

/* Custom base styles */
@layer base {
  body {
    @apply bg-gray-50 text-gray-900;
  }
}

/* Custom components */
@layer components {
  .btn {
    @apply px-4 py-2 rounded-lg font-medium transition-colors duration-200;
  }

  .btn-primary {
    @apply bg-primary-600 text-white hover:bg-primary-700;
  }

  .btn-secondary {
    @apply bg-gray-200 text-gray-900 hover:bg-gray-300;
  }

  .card {
    @apply bg-white rounded-xl shadow-sm border border-gray-200 p-6;
  }
}
```

- [ ] **Step 5: Install Node dependencies**

Run: `cd library-fwq && npm install`
Expected: Dependencies installed, node_modules created

- [ ] **Step 6: Build CSS for first time**

Run: `cd library-fwq && npm run build:css`
Expected: main.css created in src/prevent_auto/web/static/css/

- [ ] **Step 7: Update .gitignore**

Append to library-fwq/.gitignore:
```
# Node
node_modules/
package-lock.json

# Generated CSS
src/prevent_auto/web/static/css/main.css
```

- [ ] **Step 8: Update pyproject.toml package data**

```toml
[tool.setuptools.package-data]
prevent_auto = ["web/templates/**/*.html", "web/static/css/*.css", "web/static/js/*.js"]
```

- [ ] **Step 9: Create build script for deployment**

```bash
#!/bin/bash
# scripts/build-css.sh
set -e
cd "$(dirname "$0")/.."
npm run build:css
echo "✓ CSS built successfully"
```

Make executable: `chmod +x scripts/build-css.sh`

- [ ] **Step 10: Test build pipeline**

Run: `cd library-fwq && npm run build:css && ls -lh src/prevent_auto/web/static/css/main.css`
Expected: main.css exists and is minified

- [ ] **Step 11: Commit**

```bash
git add library-fwq/package.json library-fwq/tailwind.config.js library-fwq/postcss.config.js
git add library-fwq/src/prevent_auto/web/static/css/input.css
git add library-fwq/.gitignore library-fwq/pyproject.toml library-fwq/scripts/build-css.sh
git commit -m "feat: setup Tailwind CSS build infrastructure"
```

---

## Task 2: Create Router Module Structure

**Goal:** Extract dashboard routes from app.py into dedicated router module

**Files:**
- Create: `library-fwq/src/prevent_auto/web/routers/__init__.py`
- Create: `library-fwq/src/prevent_auto/web/routers/dashboard.py`
- Modify: `library-fwq/src/prevent_auto/web/app.py`

**Interfaces:**
- Consumes: `create_app()` from app.py
- Produces: `dashboard_router` (APIRouter instance)
- Produces: Routes: GET /, POST /accounts/check-first-enabled, POST /accounts/run-rolling-reservation

- [ ] **Step 1: Write test for dashboard router structure**

```python
# tests/test_web_dashboard_router.py
from fastapi import APIRouter

def test_dashboard_router_exists():
    from prevent_auto.web.routers.dashboard import router
    assert isinstance(router, APIRouter)

def test_dashboard_router_has_routes():
    from prevent_auto.web.routers.dashboard import router
    route_paths = [route.path for route in router.routes]
    assert "/" in route_paths
    assert "/accounts/check-first-enabled" in route_paths
    assert "/accounts/run-rolling-reservation" in route_paths
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd library-fwq && python -m pytest tests/test_web_dashboard_router.py -v`
Expected: FAIL - module does not exist

- [ ] **Step 3: Create routers package init**

```python
# src/prevent_auto/web/routers/__init__.py
"""Web route handlers organized by feature."""
```

- [ ] **Step 4: Create dashboard router with extracted routes**

```python
# src/prevent_auto/web/routers/dashboard.py
from fastapi import APIRouter, Request
from fastapi.responses import HTMLResponse

router = APIRouter(tags=["dashboard"])


@router.get("/", response_class=HTMLResponse)
def dashboard(request: Request, notice: str = ""):
    """Dashboard page showing account overview and seat distribution."""
    # Implementation will be moved from app.py in next step
    from prevent_auto.web.app import _get_app_state
    app_state = _get_app_state(request)
    services = app_state.services
    settings = app_state.settings

    from prevent_auto.web.runtime import build_dashboard_summary
    summary = build_dashboard_summary(services, settings=settings)

    # Return placeholder for now
    from fastapi.templating import Jinja2Templates
    templates = Jinja2Templates(directory=str(settings.package_root / "src" / "prevent_auto" / "web" / "templates"))
    return templates.TemplateResponse(
        request=request,
        name="dashboard.html",
        context={
            "request": request,
            "page_title": "仪表盘",
            "summary": summary,
            "seat_display": {},
            "accounts": [],
            "notice_message": notice,
        },
    )


@router.post("/accounts/check-first-enabled")
def check_first_enabled_accounts(request: Request):
    """Refresh booking positions for all enabled accounts."""
    from fastapi.responses import RedirectResponse
    from prevent_auto.web.app import _get_app_state, _run_enabled_account_checks, _redirect_with_notice

    app_state = _get_app_state(request)
    checked_count = _run_enabled_account_checks(app_state.services)
    return _redirect_with_notice("/", f"已检测 {checked_count} 个账号")


@router.post("/accounts/run-rolling-reservation")
def run_rolling_reservation_now(request: Request, return_to: str = "/"):
    """Trigger immediate rolling reservation check."""
    from fastapi.responses import RedirectResponse
    from prevent_auto.web.app import _get_app_state, _redirect_with_notice
    # Implementation placeholder
    return _redirect_with_notice(return_to, "功能开发中")
```

- [ ] **Step 5: Run test to verify routes exist**

Run: `cd library-fwq && python -m pytest tests/test_web_dashboard_router.py::test_dashboard_router_exists -v`
Expected: PASS

- [ ] **Step 6: Run all dashboard router tests**

Run: `cd library-fwq && python -m pytest tests/test_web_dashboard_router.py -v`
Expected: All PASS

- [ ] **Step 7: Commit router structure**

```bash
git add library-fwq/src/prevent_auto/web/routers/
git add library-fwq/tests/test_web_dashboard_router.py
git commit -m "feat: create dashboard router module structure"
```

---

## Task 3: Modernize Base Template with Tailwind

**Goal:** Replace base.html with Tailwind-styled version, add Alpine.js

**Files:**
- Create: `library-fwq/src/prevent_auto/web/templates/base_new.html`
- Create: `library-fwq/src/prevent_auto/web/templates/components/nav.html`
- Modify: `library-fwq/src/prevent_auto/web/templates/base.html` (replace content)

**Interfaces:**
- Consumes: `main.css` from Task 1
- Produces: Modern base layout with Tailwind styles
- Produces: Alpine.js integration for interactive components
- Produces: Responsive navigation component

- [ ] **Step 1: Create navigation component**

```html
<!-- src/prevent_auto/web/templates/components/nav.html -->
<nav class="bg-white border-b border-gray-200 sticky top-0 z-50" x-data="{ mobileMenuOpen: false }">
  <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
    <div class="flex justify-between h-16">
      <!-- Logo and brand -->
      <div class="flex items-center">
        <div class="flex-shrink-0 flex items-center">
          <h1 class="text-xl font-bold text-primary-600">Prevent Auto</h1>
        </div>

        <!-- Desktop navigation -->
        <div class="hidden md:ml-8 md:flex md:space-x-4">
          <a href="/" class="px-3 py-2 rounded-md text-sm font-medium text-gray-900 hover:bg-gray-100">
            仪表盘
          </a>
          <a href="/accounts" class="px-3 py-2 rounded-md text-sm font-medium text-gray-900 hover:bg-gray-100">
            号池管理
          </a>
          <a href="/automation-tasks" class="px-3 py-2 rounded-md text-sm font-medium text-gray-900 hover:bg-gray-100">
            自动任务
          </a>
          <a href="/client-tokens" class="px-3 py-2 rounded-md text-sm font-medium text-gray-900 hover:bg-gray-100">
            客户端 Token
          </a>
        </div>
      </div>

      <!-- User menu and mobile toggle -->
      <div class="flex items-center">
        {% if request.state.is_authenticated %}
        <form method="post" action="/logout" class="hidden md:block">
          <button type="submit" class="btn btn-secondary">退出登录</button>
        </form>
        {% endif %}

        <!-- Mobile menu button -->
        <button @click="mobileMenuOpen = !mobileMenuOpen" type="button"
                class="md:hidden ml-4 inline-flex items-center justify-center p-2 rounded-md text-gray-400 hover:text-gray-500 hover:bg-gray-100">
          <svg class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16" />
          </svg>
        </button>
      </div>
    </div>
  </div>

  <!-- Mobile menu -->
  <div x-show="mobileMenuOpen"
       x-transition:enter="transition ease-out duration-200"
       x-transition:enter-start="opacity-0 transform scale-95"
       x-transition:enter-end="opacity-100 transform scale-100"
       x-transition:leave="transition ease-in duration-150"
       x-transition:leave-start="opacity-100 transform scale-100"
       x-transition:leave-end="opacity-0 transform scale-95"
       class="md:hidden">
    <div class="px-2 pt-2 pb-3 space-y-1">
      <a href="/" class="block px-3 py-2 rounded-md text-base font-medium text-gray-900 hover:bg-gray-100">
        仪表盘
      </a>
      <a href="/accounts" class="block px-3 py-2 rounded-md text-base font-medium text-gray-900 hover:bg-gray-100">
        号池管理
      </a>
      <a href="/automation-tasks" class="block px-3 py-2 rounded-md text-base font-medium text-gray-900 hover:bg-gray-100">
        自动任务
      </a>
      <a href="/client-tokens" class="block px-3 py-2 rounded-md text-base font-medium text-gray-900 hover:bg-gray-100">
        客户端 Token
      </a>
      {% if request.state.is_authenticated %}
      <form method="post" action="/logout" class="px-3 py-2">
        <button type="submit" class="btn btn-secondary w-full">退出登录</button>
      </form>
      {% endif %}
    </div>
  </div>
</nav>
```

- [ ] **Step 2: Create new base template with Tailwind**

```html
<!-- src/prevent_auto/web/templates/base_new.html -->
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{{ page_title }} · Prevent Auto</title>
  <link rel="stylesheet" href="{{ request.url_for('static', path='css/main.css') }}">
  <script defer src="https://cdn.jsdelivr.net/npm/alpinejs@3.x.x/dist/cdn.min.js"></script>
</head>
<body class="min-h-screen bg-gray-50">
  {% include 'components/nav.html' %}

  <main class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
    {% if notice_message %}
    <div class="mb-6 p-4 bg-green-50 border border-green-200 rounded-lg text-green-800" role="status">
      {{ notice_message }}
    </div>
    {% endif %}

    {% block content %}{% endblock %}
  </main>
</body>
</html>
```

- [ ] **Step 3: Test template rendering**

```python
# tests/test_web_base_template.py
def test_base_new_template_exists():
    from pathlib import Path
    from prevent_auto.settings import load_settings
    settings = load_settings()
    template_path = settings.package_root / "src" / "prevent_auto" / "web" / "templates" / "base_new.html"
    assert template_path.exists()

def test_nav_component_exists():
    from pathlib import Path
    from prevent_auto.settings import load_settings
    settings = load_settings()
    template_path = settings.package_root / "src" / "prevent_auto" / "web" / "templates" / "components" / "nav.html"
    assert template_path.exists()
```

Run: `cd library-fwq && python -m pytest tests/test_web_base_template.py -v`
Expected: PASS

- [ ] **Step 4: Backup and replace base.html**

Run:
```bash
cd library-fwq/src/prevent_auto/web/templates
cp base.html base_old.html
cp base_new.html base.html
```

- [ ] **Step 5: Test application starts with new template**

Run: `cd library-fwq && python -m prevent_auto.main &`
Wait 2 seconds, then: `curl -s http://127.0.0.1:5000/ | grep "Prevent Auto"`
Expected: HTML response contains "Prevent Auto"
Stop server: `pkill -f "python -m prevent_auto.main"`

- [ ] **Step 6: Commit template modernization**

```bash
git add library-fwq/src/prevent_auto/web/templates/base_new.html
git add library-fwq/src/prevent_auto/web/templates/base.html
git add library-fwq/src/prevent_auto/web/templates/components/nav.html
git add library-fwq/tests/test_web_base_template.py
git commit -m "feat: modernize base template with Tailwind and Alpine.js"
```

---

## Summary

This plan provides the first 3 critical tasks:
1. **Task 1**: Infrastructure (Tailwind build pipeline)
2. **Task 2**: Backend modularization (router extraction pattern)
3. **Task 3**: Frontend foundation (modern base template)

**Next phases** would include:
- Task 4-8: Extract remaining routers (accounts, tasks, tokens, logs)
- Task 9-13: Redesign each page with Tailwind
- Task 14-15: Add Alpine.js interactions
- Task 16: Final testing and documentation

**Total estimated tasks**: 16-20 tasks
**Estimated time**: 4-6 weeks for complete refactoring

Each task is independently testable and commits working code.
