"""account-pool-tri-sync 客户端同步 REST API 路由子包.

本子包按 spec ``account-pool-tri-sync`` 的 task 8.2 / 8.3 拆分两个独立路由模块：

* :mod:`prevent_auto.web.api.active_account` —— Active_Account_Sync_API（接口 A
  / 接口 B）+ 拉黑事件上报。
* :mod:`prevent_auto.web.api.automation_task` —— Automation_Task_Sync_API
  下行 / 上行 PUT / 上行 DELETE。

每个模块都对外暴露 ``router`` 与 ``register(app, ...)``：``register`` 负责把路由
挂上 FastAPI 应用、补齐异常处理器，并把对应的 service 安装到 ``app.state``。
"""
