"""Tests for dashboard router structure."""
from fastapi import APIRouter


def test_dashboard_router_exists():
    """Verify dashboard router module exports an APIRouter instance."""
    from prevent_auto.web.routers.dashboard import router
    assert isinstance(router, APIRouter)


def test_dashboard_router_has_routes():
    """Verify dashboard router contains expected route paths."""
    from prevent_auto.web.routers.dashboard import router
    route_paths = [route.path for route in router.routes]
    assert "/" in route_paths
    assert "/accounts/check-first-enabled" in route_paths
    assert "/accounts/run-rolling-reservation" in route_paths
