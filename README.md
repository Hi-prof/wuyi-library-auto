# wuyi-library-auto

仓库按运行环境拆成三个子项目：

- `library-window/`：Windows 客户端与打包工程
- `library-fwq/`：服务端守护服务
- `library-android/`：Android 客户端
- `fwq-qd.bat`：放在根目录的服务端启动脚本，会自动注入两个 Python 子项目的 `src` 路径

常用命令：

```powershell
python .\scripts\verify_all.py
python .\scripts\release_check.py
```

启动 Windows 客户端：

```powershell
cd library-window
uv run wuyi-seat-bot web
```

启动后会自动打开浏览器；如果没有自动打开，访问 `http://127.0.0.1:8765`。
需要看直接日志时使用 `uv run wuyi-seat-bot web --no-guard`；端口占用时可加 `--port 8766`。
打包版可在 `dist\exe\wuyi-seat-bot` 下运行 `.\wuyi-seat-bot.exe web`。

单独调试 Python 子项目：

```powershell
cd library-window
uv sync --extra test
uv run --extra test python -m pytest

cd ..\library-fwq
uv sync --extra test
$env:PYTHONPATH="..\library-window\src;src"
uv run --extra test python -m pytest
```

- Windows 客户端说明看 `library-window/README.md`
- 服务端说明看 `library-fwq/README.md`
- Android 客户端说明看 `library-android/README.md`
