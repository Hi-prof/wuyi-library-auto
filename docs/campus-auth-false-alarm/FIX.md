# 修复方案：校园网认证异常假警报

> 配套报告：`docs/campus-auth-false-alarm/REPORT.md`
> 子项目：`library-window`

## 0. 目标

让以下两类情形都不再写 `[校园网认证异常]`：

1. 学校预约站点 `wuyiu.huitu.zhishulib.com` 短时不通触发的 `degraded` 状态。
2. 机器本身已通过 WYU 校园网认证、3 个 HTTP 探测均直连命中的"已认证"状态。

同时保留：

- 用户手动点"校园网认证"按钮时的原有行为；
- 真正 `offline` 状态下的自动重连和认证逻辑。

## 1. 修复策略

采用**双层防御**（任意一层独立可堵 bug，叠加最稳）：

- **上层（恢复入口收窄）**：把"reserve/checkout 等非 checkin 动作失败 → 触发校园网重认证"的条件，从 `{"offline", "degraded"}` 收窄到 `{"offline"}`。`degraded` 只追加一句轻量提示，不跑认证。
- **下层（认证路径加"已认证"短路）**：`_authenticate_after_detection` 进入认证前，先看 `detection["networkState"]`；只要不是 `offline`，就平静返回 `None`，不调 `_perform_campus_network_login`、不写日志。

### 1.1 为什么不一刀切只改下层

只改下层（"非 offline 就不打认证"）也能消除假日志，但保留了一个语义错误的入口：上层会因为 `degraded` 给用户的预约结果追加一句 "网络检测异常，自动重连失败：……"，仍然误导用户。所以两层都改。

### 1.2 为什么不直接改 `_perform_campus_network_login` 检测"已认证"

这条路径也可以走（"3 个 HTTP 探测全部直连命中 → 视为已认证"），但风险点在于：

- `discover_campus_login_url` 内部那一坨 HTTP 探测的语义本来就是"找 captive portal 入口"，不是"判定是否已认证"，硬塞额外语义容易留隐患；
- `_authenticate_after_detection` 已经能拿到 `detection.networkState`，用它做短路判断更直接。

所以这条路径作为**第三层兜底**保留为可选改动（见 §2.3），核心修复在上面两层。

## 2. 改动清单

### 2.1 上层：`web_recovery_service.py`

文件：`library-window/src/wuyi_seat_bot/web_recovery_service.py`

1. 拆分常量：

   ```python
   # 旧
   CHECKIN_NETWORK_RECOVERY_STATES = {"offline", "degraded"}

   # 新
   CHECKIN_NETWORK_RECOVERY_STATES = {"offline", "degraded"}   # 保留：签到失败仍要尽量尝试恢复
   ACTION_NETWORK_RECOVERY_STATES = {"offline"}                # 新增：非签到失败仅在确实掉网时才触发
   ```

2. 给 `is_checkin_network_failure` 之外新增一个 `is_action_network_failure`：

   ```python
   def is_action_network_failure(status: dict[str, Any]) -> bool:
       network_state = str(status.get("networkState", "")).strip()
       return network_state in ACTION_NETWORK_RECOVERY_STATES
   ```

3. 在 `recover_network_after_action_failure` 中用 `is_action_network_failure` 替换原来的 `is_checkin_network_failure`：

   ```python
   if not is_action_network_failure(detected_status):
       return original_result
   ```

4. `degraded` 时给原始结果追加一句更准确的提示，而不是 "网络检测异常"：

   ```python
   if str(detected_status.get("networkState", "")).strip() == "degraded":
       return append_checkin_recovery_message(
           original_result,
           f"学校目标站点暂时不通：{read_status_message(detected_status)}",
       )
   ```

   把这一段放在 `is_action_network_failure(detected_status)` 判定之前，作为"degraded 短路 + 友好提示"分支。本次修复落地该分支，避免调用方仍看到"自动重连失败"类误导提示。

### 2.2 下层：`network_monitor.py`

文件：`library-window/src/wuyi_seat_bot/network_monitor.py`

1. 修改 `_authenticate_after_detection`：

   ```python
   def _authenticate_after_detection(
       self,
       detection: dict[str, Any],
       *,
       campus_network: dict[str, Any],
       wifi_name: str,
   ) -> dict[str, Any] | None:
       # 仅在确实掉网时才尝试校园网认证。
       # `degraded` 仅代表学校目标站点不通，强行去找 captive portal
       # 会把"已认证"误判为"未识别入口"，刷出大量假警报日志。
       if str(detection.get("networkState", "")).strip() != "offline":
           return None
       connected_interfaces = [
           str(item).strip()
           for item in detection.get("connectedInterfaces", [])
           if str(item).strip()
       ]
       if not connected_interfaces:
           return None
       success, message = _perform_campus_network_login(self.config_path, campus_network)
       ...  # 后续逻辑保持不变
   ```

2. `reconnect_once` 不需要改动；它原本就只在 `detect_once()` 返回非 `online` 时进入认证分支，加上下层短路后行为更精准。
3. `authenticate_campus_network_once`（UI 手动按钮触发）**不走 `_authenticate_after_detection`**，行为不变。

### 2.3 可选第三层兜底：`_perform_campus_network_login` 识别"已认证"

如果担心未来引入新的调用路径，可在 `_perform_campus_network_login` 入口处加一个轻量探测：

```python
if _all_http_probes_hit_direct_markers():
    return True, "校园网已处于已认证状态"
```

实现要点：

- 复用 `CAMPUS_LOGIN_HTTP_PROBE_URLS` 和 `CAMPUS_LOGIN_HTTP_PROBE_DIRECT_MARKERS`，对每个 URL 发一次 `urllib` 请求；
- 用一个**很短**的超时（例如 3 秒）避免拖慢主流程；
- 至少 2/3 命中 direct marker 才判 "已认证"，避免单点抖动误判；
- 这一层只做"快速放行"，命中后不写日志（或写一条 INFO 级的 "校园网已认证，跳过登录"），不进入原 except 路径。

本次主修复**不包含**这一层；如果上层 + 下层修复落地后还出现假警报，再单独追加。

## 3. 测试计划

### 3.1 新增单元测试

`library-window/tests/test_web_recovery_service.py`（新建文件）：

1. `recover_network_after_action_failure` + `detected_status = {"networkState": "offline", ...}` → 应调用 `monitor.reconnect_once()`。
2. `recover_network_after_action_failure` + `detected_status = {"networkState": "degraded", ...}` → **不**应调用 `reconnect_once`；返回值的 message 应包含"学校目标站点暂时不通"。
3. `recover_network_after_action_failure` + `detected_status = {"networkState": "online"}` → 直接返回 `original_result`，行为同既有。
4. `recover_checkins_after_network_failure` 的语义保持不变：用 `is_checkin_network_failure` 判定，`{"offline", "degraded"}` 都会触发恢复（保留旧用例的绿色行为）。

`library-window/tests/test_network_monitor.py`（扩展现有文件）：

1. 构造一个 `NetworkMonitor` 测试桩，让 `_perform_campus_network_login` 被替换成 mock；
2. 调用 `_authenticate_after_detection(detection={"networkState": "degraded", "connectedInterfaces": ["WLAN"]}, ...)`：
   - 断言返回 `None`；
   - 断言 mock 的 `_perform_campus_network_login` **没被调用**。
3. 调用 `_authenticate_after_detection(detection={"networkState": "offline", "connectedInterfaces": ["WLAN"]}, ...)`：
   - 断言 mock 被调用一次。
4. 调用 `_authenticate_after_detection(detection={"networkState": "offline", "connectedInterfaces": []}, ...)`：
   - 断言返回 `None`、mock 没被调用（既有行为保留）。

### 3.2 回归

```powershell
cd library-window
uv run --extra test python -m pytest
```

重点关注：

- `tests/test_network_monitor.py`
- `tests/test_web_action_service.py`（如果存在）
- 新增的 `tests/test_web_recovery_service.py`

服务端侧不直接调用本修复入口；如后续改动扩大到共享导入路径，再跑：

```powershell
cd library-fwq
$env:PYTHONPATH="..\library-window\src;src"
uv run --extra test python -m pytest
```

### 3.3 全量与发布前校验

```powershell
python .\scripts\verify_all.py
```

发布前再运行 `python .\scripts\release_check.py`；按仓库要求设置非默认服务端密钥环境变量，避免用默认密钥通过检查。

## 4. 手工回归（修复后人工核对）

1. 准备一台已通过 WYU 认证、可正常上外网、且正常情况下能访问 `wuyiu.huitu.zhishulib.com` 的机器。
2. 启用至少 1 个 reserve 计划，并把 `reserve_next_run_at` 改到近 2 分钟内。
3. 临时让预约站点不可达（如修改 `hosts` 把 `wuyiu.huitu.zhishulib.com` 指向 `127.0.0.1`），等 reserve 触发并失败。
4. 期望：
   - `runtime/logs/network-monitor.log` **不出现**新的 `[校园网认证异常]`；
   - `runtime/automation_plans.json` 中对应计划的 `reserve_last_message` 写入"学校目标站点暂时不通"类的提示；
   - `runtime/network_monitor_status.json` 仍为 `online` 或 `degraded`，不会变成 `offline`。
5. 还原 `hosts`，重新观察一次 reserve 周期，应当恢复正常成功。

## 5. 兼容性与风险

- **行为变化**：reserve/checkout 失败后，仅在 `offline` 时才会自动重新认证校园网；`degraded` 不再触发认证。
  - 这是按设计——`degraded` 是学校服务端问题，自动重认证既无效又会刷错日志。
  - 用户可手动点托盘/设置里的"校园网认证"按钮强制触发（路径不经过 `_authenticate_after_detection`，行为不变）。
- **不修改文件**：`runtime/app_settings.json`、`runtime/automation_plans.json` 的格式不变。
- **不修改密码/账号存储方式**：仍是明文，见 REPORT §8 的后续讨论。

## 6. 落地顺序

按"小步可回滚"原则推荐：

1. 先落 §2.1 上层改动 + §3.1 第 1 组单元测试 → 跑 pytest → 全绿后提交。
2. 再落 §2.2 下层改动 + §3.1 第 2 组单元测试 → 跑 pytest → 全绿后提交。
3. §3.2 / §3.3 全量验证一次。
4. 在受影响机器上灰度部署 1–2 天，确认 `network-monitor.log` 不再增长新的 `[校园网认证异常]`。
5. 视情况决定是否再追加 §2.3 的第三层兜底。

## 7. 不在本次范围内的改动（备忘）

- `campusNetwork.password` 明文存储（REPORT §8）—— 单独立项处理；
- `_authenticate_after_detection` 加 "当前 SSID 必须等于 `campus_network.wifiName`" 的强校验 —— 当前修复已足够，未来需要再加这一层硬约束时再做；
- `_perform_campus_network_login` 在 `discover_campus_login_url` 失败时的日志结构 —— 当前修复后此路径几乎不会被触发，不重要。
