# -*- mode: python ; coding: utf-8 -*-
from pathlib import Path

from PyInstaller.utils.hooks import collect_data_files
from PyInstaller.utils.hooks import collect_submodules

PROJECT_ROOT = Path.cwd()
SRC_ROOT = PROJECT_ROOT / 'src'
WEB_DIR = SRC_ROOT / 'wuyi_seat_bot' / 'web'
ICON_PATH = PROJECT_ROOT / 'assets' / 'app-icon.ico'

datas = [(str(WEB_DIR), 'wuyi_seat_bot/web')]
hiddenimports = []
datas += collect_data_files('wuyi_seat_bot')
hiddenimports += collect_submodules('PIL')
hiddenimports += collect_submodules('pystray')


a = Analysis(
    [str(SRC_ROOT / 'wuyi_seat_bot' / 'cli.py')],
    pathex=[str(SRC_ROOT)],
    binaries=[],
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='wuyi-seat-bot',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=str(ICON_PATH) if ICON_PATH.exists() else None,
)
coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=True,
    upx_exclude=[],
    name='wuyi-seat-bot',
)
