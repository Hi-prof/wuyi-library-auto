# 自动签到链路审查（2026-05-17）

## 背景

- 用户反馈：今早多账号自动签到没成功，已自行修复一轮，要求再次审查多账号自动签到的完成流程逻辑和功能。
- 已提交修复（HEAD = `80ba71a fix(android): 修复自动签到链路 9 处缺陷 (BUG 1-4, 6-10)`）。
- 当前未提交改动覆盖的文件：
  - `library-android/core/domain/src/main/kotlin/com/wuyi/libraryauto/core/domain/usecase/RunPeriodicCheckInUseCase.kt`
  - `library-android/core/runtime/src/main/java/com/wuyi/libraryauto/core/runtime/worker/PeriodicCheckInWorker.kt`
  - `library-android/app/src/main/java/com/wuyi/libraryauto/runtime/StorageBackedPeriodicCheckInRunner.kt`
  - 对应的两份测试文件
- 用户最近改动新增内容：超时日志、`failedReservations` 计数、Worker 基于 summary 决定 retry、`SignInAudit` 落库。逻辑方向正确。

## 链路总览

签到当前有 3 条路径：

| 路径 | 触发 | 入口 | 关键职责 |
| --- | --- | --- | --- |
| `ReservationGuardWorker` | `startTime - limitSignAgo` 精确定时（由手动预约 / `AutomationPlanWorker` 写入后入队） | `core/runtime/.../worker/ReservationGuardWorker.kt` | BLE 扫描 → HTTP `checkIn`，401/403 自动 `refreshLogin` |
| `PeriodicCheckInWorker` | 30 分钟周期 + 进程恢复 + 网络恢复 + 校园网认证恢复 | `core/runtime/.../worker/PeriodicCheckInWorker.kt` → `StorageBackedPeriodicCheckInRunner` → `RunPeriodicCheckInUseCase` | 兜底，所有账号纯 HTTP 签到（不扫 BLE） |
| UI 批量签到 | 用户手动 | `RunPeriodicCheckInBatchRunner` | 通过 `PeriodicCheckInRunGate` 阻断 PeriodicWorker，避免 HTTP 重叠 |

3 条路径最终都通过 `AccountSeatActionRepository.performAction(studentId, AccountSeatAction.CheckIn)` 调 `SeatBookingActionService.checkIn`（GuardWorker 直接调底层 `dependencies.checkIn`）。

## 缺陷清单（按严重程度排序）

### BUG-A（P1，最可能的今早未签到根因） — Cookie 过期时多账号兜底签到没有自我恢复机制

- 位置：`library-android/app/src/main/java/com/wuyi/libraryauto/ui/repository/task/AccountSeatActionRepository.kt:115-127`
- 现象：
  ```kotlin
  private suspend fun ensureSession(studentId: String): AuthenticatedSession {
      sessionRepository.currentSession(studentId)?.let { return it }
      ...
  }
  ```
  `PersistentSessionRepository` 把 cookie 持久化到 SharedPreferences。第二天 `PeriodicCheckInWorker` 启动时，`currentSession(studentId)` 一定不为 null（旧 cookie 还在），但实际 cookie 已过期。
- 后果链路：
  1. `loadResolvedRemoteSnapshot` 用旧 cookie 调 `loadCurrentBooking`。
  2. `SeatBookingStatusService.parseBookingPayload` 把登录态过期识别为 `liveState = NEED_LOGIN`，**不抛异常**。
  3. `AccountSeatActionRepository.performAction:55` 取 `bookingId = snapshot.bookingId ?: error("当前没有可操作预约")`，错误信息不含"登录"。
  4. `StorageBackedPeriodicCheckInRunner.PerformActionSignInExecutor.toSignInError` 走 `text.contains("登录")` 文本匹配 → `SignInError.Unknown`。
  5. `Worker.retry`，但 cookie 仍然是旧的，无限循环失败。
- 对比 `ReservationGuardWorker.kt:230-258`：拿到 401/403 会调 `dependencies.refreshLogin(account)` 用 `SavedAccountStore` 里的密码重新登录。**周期路径 / UI 单点签到 / UI 批量签到都缺这层兜底。**
- 修复方向（最小改动）：
  - `AccountSeatActionRepository.ensureSession` 增加 `forceRefresh: Boolean = false` 参数，`forceRefresh=true` 时跳过 `sessionRepository.currentSession` 复用。
  - `performAction` 与 `loadSnapshot` 在识别到 `NEED_LOGIN` 或 401/403 时，调用 `sessionRepository.remove(studentId)` 后用 `ensureSession(studentId, forceRefresh=true)` 重试一次（仅一次，避免无限递归）。
  - 三条路径同时受益。

### BUG-B（P2）— 多账号串行处理 + Worker 5 分钟超时导致整轮失败

- 位置：
  - `library-android/core/domain/src/main/kotlin/com/wuyi/libraryauto/core/domain/usecase/RunPeriodicCheckInUseCase.kt:24-43`
  - `library-android/core/runtime/src/main/java/com/wuyi/libraryauto/core/runtime/worker/PeriodicCheckInWorker.kt`（`RUN_TIMEOUT_MILLIS = 5 * 60_000L`）
- 现象：
  - `singleAccountTimeoutMillis = 60_000L`（每账号 60 秒）。
  - Worker 整体 `RUN_TIMEOUT_MILLIS = 5 * 60_000L`。
  - 账号间 `processAccount` 是 **串行** for 循环。
- 后果：账号 ≥ 5 个、登录或接口慢时，Worker 5 分钟被 `withTimeoutOrNull` 截断，`summary == null`，已成功的账号状态写不回报告，整轮 retry。下一次 retry 仍可能继续超时，签到窗口可能直接错过。
- 修复方向：
  - 账号间改成并发：`coroutineScope { accounts.map { async { processAccount(...) } }.awaitAll() }`。
  - 用 `Semaphore` 控制并发度（建议 3-4），避免学校接口限流。
  - 或将 Worker 整体超时拉长到 10-15 分钟（仍小于 30 分钟周期）。

### BUG-C（P3）— 周期路径错误映射文本匹配过于粗糙

- 位置：`library-android/app/src/main/java/com/wuyi/libraryauto/runtime/StorageBackedPeriodicCheckInRunner.kt:217-224`
- 现象：
  ```kotlin
  private fun Throwable.toSignInError(): SignInError {
      val text = message.orEmpty()
      return when {
          text.contains("已签到") || text.contains("重复签到") -> SignInError.AlreadySignedIn
          text.contains("登录") -> SignInError.ServerRejected
          else -> SignInError.Unknown
      }
  }
  ```
  `AccountSeatActionRepository.normalizeSeatActionError` 已经把所有错误封装成"执行签到失败：…"。`text.contains` 这种判断会让"不在签到时间"、"网络超时"、"HTTP 5xx"全部落到 `Unknown`。
- 后果：
  1. `summary.failedReservations` 计数失真。
  2. 用户最近的修改 `failedReservations > 0 → Result.retry()`，对"不在签到时间"这种**不该 retry** 的业务失败也会 retry。
- 修复方向：
  - 让 `AccountSeatActionRepository.performAction` 抛 typed 异常，或在 `AccountSeatActionExecutionResult` 中携带 `signInError` 字段，不再在文本上做判断。
  - 复用现有的 `SeatBookingErrorMapper.fromMessage`，把已识别好的 `SignInError` 透传到 `PerformActionSignInExecutor`。

### BUG-D（P4）— `PeriodicCheckInWorker.runOnceNow` 没设 backoff

- 位置：`library-android/core/runtime/src/main/java/com/wuyi/libraryauto/core/runtime/worker/PeriodicCheckInWorker.kt:75-79`
- 现象：
  ```kotlin
  internal fun buildRunOnceRequest(source: TriggerSource): OneTimeWorkRequest =
      OneTimeWorkRequestBuilder<PeriodicCheckInWorker>()
          .setInputData(workDataOf(KEY_TRIGGER_SOURCE to source.name))
          .build()
  ```
  未设置 `setBackoffCriteria`，retry 走 WorkManager 默认指数退避（30 秒起，5 小时上限）。
- 后果：在 BUG-A 未修复的前提下，`ProcessRestart`/`NetworkRestored`/`CampusAuthRecovery` 触发时如果一直 retry，可能产生指数膨胀的密集失败重试。
- 修复方向：与 PeriodicWorkRequest 对齐 `setBackoffCriteria(BackoffPolicy.LINEAR, 60, TimeUnit.SECONDS)`。

### BUG-E（P5）— UI 批量签到不写 `SignInAuditRepository`

- 位置：`library-android/app/src/main/java/com/wuyi/libraryauto/ui/repository/task/RunPeriodicCheckInBatchRunner.kt`
- 现象：`AccountSeatActionSignInExecutor.attempt` 只更新 UI 行状态，没有写 `SignInAuditRepository`。
- 后果：UI 批量签到的尝试不会出现在"签到监控"页，与 `StorageBackedPeriodicCheckInRunner.PerformActionSignInExecutor` 不一致。
- 修复方向：把审计写入抽到一个共享的 helper / 装饰器，让两条 Periodic 路径都走同一个 audit 写入逻辑。

### BUG-F（P6）— `PeriodicCheckInRunGate.shared` 缺少进程前提注释

- 位置：`library-android/core/runtime/src/main/java/com/wuyi/libraryauto/core/runtime/worker/PeriodicCheckInWorker.kt`
- 现象：`PeriodicCheckInRunGate.shared` 是 JVM 进程级单例。当前 app 单进程 OK。
- 后果：未来若 Worker 跑在独立进程，`runGate` 互斥失效，会出现 UI 批量签到与 PeriodicWorker 同时调 HTTP。
- 修复方向：添加 KDoc 明确"仅适用于单进程"，或改成 SharedPreferences/文件锁形式。

### BUG-G（P7）— `RunPeriodicCheckInUseCase` 的 `failed` 计数没有显式排除 `AlreadySignedIn`

- 位置：`library-android/core/domain/src/main/kotlin/com/wuyi/libraryauto/core/domain/usecase/RunPeriodicCheckInUseCase.kt:72-77`
- 现象：
  ```kotlin
  if (result.signInError != null) {
      failed += 1
  }
  ```
  当前 `AccountSeatActionRepository`（BUG 7 修复）已经把 `AlreadySignedIn` 视为成功，不会把 `signInError == AlreadySignedIn` 的结果传上来。逻辑上暂时 OK。
- 后果：未来 executor 行为变更时，`AlreadySignedIn` 可能被误计入 `failed`，触发不必要的 Worker retry。
- 修复方向：显式 `if (result.signInError != null && result.signInError != SignInError.AlreadySignedIn)`，做防御。

### BUG-H（P8）— `PeriodicCheckInWorker` 周期 30 分钟可能错过短窗口

- 位置：`library-android/core/runtime/src/main/java/com/wuyi/libraryauto/core/runtime/worker/PeriodicCheckInWorker.kt:67-72`
- 现象：周期 30 分钟，flex 10 分钟。学校签到窗口若短于 30 分钟，单纯靠 Periodic 兜底覆盖率不足。
- 当前主签到路径是 `ReservationGuardWorker`，PeriodicWorker 仅是兜底，所以问题不算严重。
- 修复方向：观察实际窗口长度后决定是否缩到 15 分钟（WorkManager PeriodicWorkRequest 最低值）。

## 修复 TODO

- [x] **BUG-A**：`AccountSeatActionRepository` 增加 `ensureSession(forceRefresh)` + 检测 `NEED_LOGIN`/401/403 后 `refreshLogin` 重试一次。三条路径同享。
- [x] **BUG-B**：`RunPeriodicCheckInUseCase` 改成账号间并发（`Semaphore` 上限 4），`PeriodicCheckInWorker.RUN_TIMEOUT_MILLIS` 从 5 分钟扩到 10 分钟。
- [x] **BUG-C**：`AccountSeatActionExecutionResult` 携带 `signInError`；新增 `SeatActionFailedException` typed 异常；`PerformActionSignInExecutor.toSignInError` 改成读取已识别的错误码，去掉文本匹配。
- [x] **BUG-D**：`PeriodicCheckInWorker.buildRunOnceRequest` 增加 `setBackoffCriteria(BackoffPolicy.LINEAR, 60, TimeUnit.SECONDS)`。
- [x] **BUG-E**：`RunPeriodicCheckInBatchRunner` 注入 `SignInAuditRepository`，`AccountSeatActionSignInExecutor` 写审计；`AppDependencies` 注入到位。
- [x] **BUG-F**：给 `PeriodicCheckInRunGate` 添加单进程前提 KDoc。
- [x] **BUG-G**：`RunPeriodicCheckInUseCase` 的 `failed` 判定显式排除 `AlreadySignedIn`。
- [ ] **BUG-H**：观察实际签到窗口长度后再决定是否缩短周期，本轮不强制修。

## 优先级建议

- 立即修：BUG-A、BUG-B、BUG-C、BUG-D。覆盖最大概率的"今早没签到"原因，并避免修完 BUG-A 后还会被错误映射拖累。
- 下一轮再修：BUG-E、BUG-F、BUG-G。
- 仅观察：BUG-H。

## 验证策略（修完后）

- 最小验证：`cd library-android; .\gradlew.bat --no-daemon --max-workers=1 --console=plain testDebugUnitTest`
- 重点测试目录：
  - `core/domain/src/test/kotlin/.../usecase/RunPeriodicCheckInUseCaseTest.kt`
  - `core/runtime/src/test/kotlin/.../worker/PeriodicCheckInWorkerTest.kt`
  - `app/src/test/java/.../ui/repository/task/AccountSeatActionRepositoryTest.kt`（覆盖 BUG-A 的会话失效自愈）
- 用户自行编译、自行运行，本审查不生成测试脚本、不触发 build。
