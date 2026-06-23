# Bug 报告：校园网认证异常假警报

> 子项目：`library-window`
> 涉及文件：`network_monitor.py`、`web_recovery_service.py`、`web_action_service.py`
> 发现时间：2026-05-12
> 影响版本：`library-window` 当前主干（任何启用了 `campusNetwork.enabled=true` 的环境均可复现）

## 1. 现象

一台**长期在校园网内、实际联网完全正常**的机器，`runtime/logs/network-monitor.log` 中周期性出现大段 `[校园网认证异常]` 日志。单台机器上 4 天样本表格合计 43 条，分布如下：

| 日期 | 次数 | 时间窗口 |
| --- | --- | --- |
| 2026-05-09 | 2 | 05:17 – 05:18 |
| 2026-05-10 | 19 | 05:04 – 05:18 |
| 2026-05-11 | 16 | 05:05 – 05:18 |
| 2026-05-12 | 6 | 05:13 – 05:18 |

每条日志形如：

```
2026-05-10 05:04:29 [校园网认证异常]
登录地址：(空)
登录域名：(空)
账号：202******30
结果：ValueError: 未识别校园网认证入口，请确认已连接 WYU（HTTP 探测和浏览器兜底均失败）
HTTP 探测：http://www.msftconnecttest.com/connecttest.txt -> 命中直连特征，未被校园网拦截
HTTP 探测：http://captive.apple.com/ -> 命中直连特征，未被校园网拦截
HTTP 探测：http://connectivitycheck.gstatic.com/generate_204 -> 命中直连特征，未被校园网拦截
浏览器探测：http://baidu.com/ -> https://www.baidu.com/，未识别校园网登录地址
提示：未识别校园网认证入口，请确认已连接 WYU（HTTP 探测和浏览器兜底均失败）
```

而同一时段 `service-supervisor.log` 里每 2 小时一次的常规巡检仍然是 `网络连接正常`，仅凌晨 05:00 左右偶尔出现一次 `学校目标站点连通性检测失败`（`degraded`）。

## 2. 关键误导点

日志里 3 条 HTTP 探测都写 `命中直连特征，未被校园网拦截`，**不代表**"没在校园网"。它的真实含义是：

- 未认证时，WYU 门户会把 `connectivitycheck.gstatic.com/generate_204` 等探测 URL 劫持到登录页，响应体里就看不到 `Microsoft Connect Test`、`<TITLE>Success</TITLE>` 这些 direct marker；
- **已认证**时，所有探测 URL 直接走外网，每条都命中 direct marker，"没被拦截"。

所以"全部直连命中"=当前已经成功登录上 WYU 校园网、可以正常上外网，恰恰是正常状态，不是异常。

## 3. 触发链路（代码级）

1. 配置文件 `runtime/app_settings.json`：
   - `campusNetwork.enabled = true`、`wifiName = "WYU"`、账号/密码已填。
2. `runtime/automation_plans.json` 有 11 个启用的预约计划，`reserve_check_interval_minutes = 30`，所有 `reserve_next_run_at` 几乎同一时刻到期。
3. 每天凌晨 05:00 前后，学校预约服务器 `https://wuyiu.huitu.zhishulib.com/` 短时不通。`NetworkMonitor.detect_once()` 返回 `networkState = "degraded"`、`message = "学校目标站点连通性检测失败"`。
4. 11 个 `reserve` 动作依次失败。非 `checkin` 动作失败后走 `recover_network(result)`：

    ```@c:/Users/xuhuangbin/Desktop/wuyi-library-auto/library-window/src/wuyi_seat_bot/web_action_service.py:37-44
        if not result.success:
            if action == ActionType.CHECKIN:
                recovered_result = recover_checkins(account_name, result)
                if recovered_result is not None:
                    return recovered_result
            else:
                return recover_network(result)
    ```

5. `recover_network_after_action_failure` 把 `degraded` 也视作"网络异常"并触发重连：

    ```@c:/Users/xuhuangbin/Desktop/wuyi-library-auto/library-window/src/wuyi_seat_bot/web_recovery_service.py:10-11
    CHECKIN_NETWORK_RECOVERY_STATES = {"offline", "degraded"}
    CHECKIN_RECOVERY_PENDING_STATUSES = {"0"}
    ```

    ```@c:/Users/xuhuangbin/Desktop/wuyi-library-auto/library-window/src/wuyi_seat_bot/web_recovery_service.py:86-114
        if not recovery_lock.acquire(blocking=False):
            return original_result
        try:
            try:
                monitor = get_network_monitor()
                detected_status = monitor.detect_once()
            except Exception:  # noqa: BLE001
                return original_result
            if not is_checkin_network_failure(detected_status):
                return original_result

            try:
                reconnect_status = monitor.reconnect_once()
    ```

6. `NetworkMonitor.reconnect_once()` 第一步就是 `_authenticate_after_detection`，它只判 `connectedInterfaces` 非空就开打，**不区分当前是 `offline` 还是 `degraded`、也不判当前 SSID 是不是 `WYU`**：

    ```@c:/Users/xuhuangbin/Desktop/wuyi-library-auto/library-window/src/wuyi_seat_bot/network_monitor.py:399-424
        def _authenticate_after_detection(
            self,
            detection: dict[str, Any],
            *,
            campus_network: dict[str, Any],
            wifi_name: str,
        ) -> dict[str, Any] | None:
            connected_interfaces = [str(item).strip() for item in detection.get("connectedInterfaces", []) if str(item).strip()]
            if not connected_interfaces:
                return None
            success, message = _perform_campus_network_login(self.config_path, campus_network)
    ```

7. `_perform_campus_network_login` 在 `loginUrl` 为空时走 `discover_campus_login_url` 找 captive portal：
   - 3 个 HTTP 探测 URL 全部直连返回（因为**已经认证**），认为"没找到被拦截的入口"；
   - 浏览器兜底打开 `http://baidu.com/`，也正常重定向到 `https://www.baidu.com/`，不是登录页；
   - 抛 `ValueError: 未识别校园网认证入口` → `except` 分支写一条 `[校园网认证异常]`（`network_monitor.py:773-792`）。

8. 单次探测 + 浏览器兜底约 50–60 秒（`CAMPUS_LOGIN_BROWSER_DISCOVERY_TIMEOUT_SECONDS = 45` + 各项 HTTP 超时）。11 个计划串行轮一遍就是 11 条左右；如果预约站点短时不可用跨过 30 分钟窗口，还会再叠一轮。

## 4. 根因总结

两层问题叠加：

- **误诊（上层）**：`recover_network_after_action_failure` 把 `degraded`（仅学校目标站点不通）当成"网络异常需要重新认证"。`degraded` 本质是服务端问题，跟本机校园网认证状态无关。
- **误判（下层）**：`_authenticate_after_detection` / `_perform_campus_network_login` 没有"已认证"这一分支。3 个 HTTP 探测全部直连命中时，应该判为"已在认证状态"并平静退出；现在反而抛 `ValueError` 并按"认证失败"登账。

只要两层中任意一层修掉，就能阻止假警报；同时修掉，鲁棒性最高。

## 5. 复现条件

1. `campusNetwork.enabled = true`，账号密码已填，机器处于"**已通过 WYU 认证**"状态。
2. 至少 1 个启用中的 `reserve` 计划，且 `reserve_next_run_at` 到期时学校预约站点短时不可达（人为可用 `hosts` 指向回环或断开学校内网段来模拟）。
3. 观察 `runtime/logs/network-monitor.log` 是否出现 `[校园网认证异常]`，`runtime/network_monitor_status.json` 的 `networkState` 最终应为 `online` 或 `degraded`，不应变成 `offline`。

预期：只写一条"学校目标站点暂时不通"的轻量提示；或根本不写日志。
实际：每个到期计划都写一条 `[校园网认证异常]`。

## 6. 影响面

- **日志噪声**：用户看到大量"校园网认证异常"会以为账号/密码/认证系统坏了，实际什么都没坏。
- **真实故障被淹没**：如果以后真的出现"WYU 认证掉线"，与这些假警报混在一起无法区分。
- **无功能损害**：通用网络巡检仍然写 `网络连接正常`，预约/签到核心功能不受影响；学校目标站点恢复后 reserve 会自动重试成功。

## 7. 用户侧临时规避

在修复发布前，受影响用户可任选其一：

- 把 `runtime/app_settings.json` 里 `campusNetwork.enabled` 改成 `false`；或
- 清空 `campusNetwork.username` / `campusNetwork.password`。

上述两种改动都会让 `_perform_campus_network_login` 在很早阶段就短路返回，不再写 `校园网认证异常`。副作用是今后确实掉网时不再自动重登校园网，需要手动在托盘/设置里点"校园网认证"。

## 8. 相关观察（非 bug，但顺手记录）

- `runtime/app_settings.json` 目前以**明文**保存 `campusNetwork.password`。该文件在 `.gitignore` 内不会被提交，但用户发截图/日志前务必脱敏；后续可考虑加密或改用环境变量。
- `preferredWifiNames` 为空、`network_monitor_status.json.wifiName` 为空，说明现在 `_authenticate_after_detection` 路径上完全没校验过当前 SSID == `campus_network.wifiName`，是顺带可加固的点（见 `FIX.md`）。
