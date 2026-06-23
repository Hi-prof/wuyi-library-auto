from __future__ import annotations

import logging
import os

import uvicorn

from prevent_auto.settings import load_settings
from prevent_auto.web.app import create_app


def _configure_logging() -> None:
    """配置应用层 logger 默认输出到控制台。

    uvicorn 默认只为 ``uvicorn.*`` 配置 handler，``prevent_auto.*`` 的 logger 会
    沿 root 走 ``WARNING``，INFO 调试日志看不到。这里在不覆盖 uvicorn 行为的前提
    下，把 ``prevent_auto`` 的级别拉到 INFO 并挂一个 StreamHandler，方便排查
    自动任务批量启停等链路问题。
    """

    level_name = os.getenv("PREVENT_AUTO_LOG_LEVEL", "INFO").upper()
    level = getattr(logging, level_name, logging.INFO)

    app_logger = logging.getLogger("prevent_auto")
    app_logger.setLevel(level)
    if not any(
        isinstance(h, logging.StreamHandler) for h in app_logger.handlers
    ):
        handler = logging.StreamHandler()
        handler.setFormatter(
            logging.Formatter(
                "%(asctime)s [%(levelname)s] %(name)s: %(message)s"
            )
        )
        app_logger.addHandler(handler)
        # 避免重复打印：已挂自有 handler，就不再向 root 传播
        app_logger.propagate = False


def main() -> int:
    _configure_logging()
    settings = load_settings()
    app = create_app(settings)
    uvicorn.run(app, host=settings.host, port=settings.port)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
