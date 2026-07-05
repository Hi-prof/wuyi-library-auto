# 图书馆自动预约系统优化实施计划

## 优化目标

根据用户反馈，针对Android手机版本进行以下优化：

1. **UI体验优化**：提升前端UI反馈强度，改善页面布局，减少冗余信息
2. **快捷操作增强**：新增一键签到和一键补约功能
3. **智能座位推荐**：基于历史记录自动推荐座位
4. **网络状态优化**：改善断网后的提示和操作选项

---

## 一、座位页面UI优化

### 1.1 问题诊断
- UI反馈不强，用户操作后缺乏明显的视觉反馈
- 页面信息过于冗余，关键信息不够突出
- 缺少快捷操作按钮

### 1.2 优化方案

#### UI反馈增强
- **刷新操作**：添加动画效果和进度提示
- **操作反馈**：签到/取消操作后显示Toast提示和卡片状态变化
- **状态区分**：使用更明显的颜色和图标区分不同状态

#### 信息精简
- 移除重复性说明文字
- 简化状态标签，使用图标+简短文字
- 优化卡片布局，突出关键信息（座位号、状态、操作按钮）

#### 文件修改
- `SeatDisplayScreen.kt` - 优化页面布局和反馈
- `SeatDisplayCard.kt` - 简化卡片信息展示
- `SeatDisplayViewModel.kt` - 增强状态管理

---

## 二、一键签到功能

### 2.1 功能设计

**位置**：座位概览页面顶部卡片

**触发条件**：
- 存在处于"待签到"状态的预约
- 账号已认证且网络正常

**执行逻辑**：
1. 筛选所有 `RESERVED_WAITING_SIGNIN` 状态的预约
2. 串行执行签到操作（复用现有 `AccountSeatActionRepository`）
3. 实时更新UI状态和进度
4. 完成后显示成功/失败统计

### 2.2 实现细节

#### 新增Repository方法
```kotlin
// SeatDisplayRepository.kt
suspend fun batchCheckIn(): BatchCheckInResult {
    val waitingCards = readCachedFromLocal().filter {
        it.liveState == SeatBookingLiveState.RESERVED_WAITING_SIGNIN
    }
    val results = mutableListOf<CheckInResult>()

    waitingCards.forEach { card ->
        val result = actionExecutor.performAction(
            studentId = card.studentId,
            action = AccountSeatAction.CheckIn,
            bookingId = card.bookingId
        )
        results.add(CheckInResult(card.studentId, result))
    }

    return BatchCheckInResult(
        total = waitingCards.size,
        success = results.count { it.isSuccess },
        failed = results.count { !it.isSuccess },
        details = results
    )
}
```

#### ViewModel支持
```kotlin
// SeatDisplayViewModel.kt
fun batchCheckIn() {
    viewModelScope.launch {
        _uiState.update { it.copy(isBatchCheckingIn = true) }
        val result = repository.batchCheckIn()
        _uiState.update {
            it.copy(
                isBatchCheckingIn = false,
                batchCheckInResult = result
            )
        }
        // 刷新全部卡片状态
        loadInitialSnapshot()
    }
}
```

#### UI组件
在 `SeatDisplayScreen.kt` 的顶部卡片添加按钮：
```kotlin
FilledTonalButton(
    onClick = viewModel::batchCheckIn,
    enabled = !uiState.isBatchCheckingIn && waitingSignIn > 0,
    modifier = Modifier.fillMaxWidth()
) {
    Icon(Icons.Outlined.TouchApp, contentDescription = null)
    Spacer(Modifier.width(6.dp))
    Text(if (isBatchCheckingIn) "正在签到..." else "一键签到 ($waitingSignIn)")
}
```

---

## 三、一键补约功能

### 3.1 功能设计

**位置**：座位概览页面顶部卡片

**数据源**：
- `AccountReservationHistoryReader` - 读取最近5-7条预约记录
- 包含来源：`HISTORY_PLAN`, `RESERVATION_TASK`, `SEAT_SNAPSHOT`, `PREFERRED_SEAT`

**智能判断逻辑**：
1. 合并所有账号的历史记录
2. 统计每个 `(roomName, seatNumber)` 组合的出现频率
3. 选择出现频率最高的座位作为补约目标
4. 如果频率相同，优先选择最近使用的

**补约目标日期**：
- 今天（如果当前时间 < 23:00）
- 明天
- 后天

**时间段**：8:00-22:00（14小时）

### 3.2 实现细节

#### 新增智能座位选择器
```kotlin
// SmartSeatRecommender.kt
class SmartSeatRecommender(
    private val historyReader: AccountReservationHistoryReader
) {
    suspend fun recommendSeat(studentIds: List<String>): SeatRecommendation? {
        val allHistory = studentIds.flatMap {
            historyReader.loadHistory(it)
        }

        if (allHistory.isEmpty()) return null

        // 按 (roomName, seatNumber) 分组并统计
        val seatFrequency = allHistory
            .groupBy { it.roomName to it.seatNumber }
            .mapValues { (_, hits) ->
                SeatFrequency(
                    count = hits.size,
                    latestTimestamp = hits.maxOf { it.timestampEpochSeconds }
                )
            }

        // 选择频率最高且最近使用的座位
        val (roomSeat, freq) = seatFrequency.maxWithOrNull(
            compareBy<Map.Entry<Pair<String, String>, SeatFrequency>> { it.value.count }
                .thenBy { it.value.latestTimestamp }
        ) ?: return null

        return SeatRecommendation(
            roomName = roomSeat.first,
            seatNumber = roomSeat.second,
            confidence = freq.count.toDouble() / allHistory.size,
            usageCount = freq.count
        )
    }
}

data class SeatRecommendation(
    val roomName: String,
    val seatNumber: String,
    val confidence: Double,
    val usageCount: Int
)
```

#### 批量补约Repository
```kotlin
// SeatDisplayRepository.kt
suspend fun batchMakeUpReservations(
    recommendation: SeatRecommendation
): BatchReservationResult {
    val accounts = getAuthenticatedAccounts()
    val targetDates = calculateTargetDates()
    val results = mutableListOf<ReservationResult>()

    for (account in accounts) {
        for (date in targetDates) {
            val selection = ManualReservationSelection(
                studentId = account.studentId,
                entryUrl = SchoolPortalConfig.SeatEntryUrls.first(),
                roomId = "", // 需要查询获取
                roomName = recommendation.roomName,
                seatNumber = recommendation.seatNumber,
                beginTimeEpochSeconds = date.atTime(8, 0).toEpochSecond(),
                durationSeconds = 14 * 3600 // 14小时
            )

            val result = manualReservationGateway.reserve(selection)
            results.add(ReservationResult(account.studentId, date, result))
        }
    }

    return BatchReservationResult(results)
}

private fun calculateTargetDates(): List<LocalDate> {
    val now = LocalDateTime.now()
    val today = now.toLocalDate()

    return buildList {
        // 今天（如果时间早于23:00）
        if (now.hour < 23) add(today)
        // 明天
        add(today.plusDays(1))
        // 后天
        add(today.plusDays(2))
    }
}
```

#### UI组件
```kotlin
// SeatDisplayScreen.kt
FilledTonalButton(
    onClick = viewModel::showMakeUpDialog,
    enabled = cardCount > 0,
    modifier = Modifier.fillMaxWidth()
) {
    Icon(Icons.Outlined.EventRepeat, contentDescription = null)
    Spacer(Modifier.width(6.dp))
    Text("一键补约")
}

// 补约确认对话框
if (uiState.showMakeUpDialog) {
    AlertDialog(
        onDismissRequest = { viewModel.dismissMakeUpDialog() },
        title = { Text("一键补约") },
        text = {
            Column {
                Text("根据历史记录推荐座位：")
                Text(
                    "${recommendation.roomName} ${recommendation.seatNumber}号",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text("使用次数：${recommendation.usageCount}次")
                Text("将为所有账号预约今明后三天")
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::executeMakeUp) {
                Text("确认补约")
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissMakeUpDialog) {
                Text("取消")
            }
        }
    )
}
```

---

## 四、自动任务智能座位推荐

### 4.1 功能设计

**触发时机**：
- 用户选择账号后
- 座位字段为空时
- 自动填充推荐座位

**数据源**：复用 `AccountReservationHistoryReader`

**UI提示**：
- 显示推荐置信度
- 允许用户修改或拒绝推荐

### 4.2 实现细节

#### 增强现有自动填充逻辑
```kotlin
// AutomationTaskViewModel.kt
fun updateDialogStudentId(studentId: String) {
    // ... 现有代码 ...

    // 如果历史记录返回多条，显示推荐信息
    viewModelScope.launch {
        val history = historyReader.loadHistory(safeStudentId)
        val recommendation = SmartSeatRecommender.analyzeHistory(history)

        if (recommendation != null && recommendation.confidence > 0.5) {
            uiState = uiState.copy(
                dialog = currentDialog.copy(
                    roomName = recommendation.roomName,
                    seatNumber = recommendation.seatNumber,
                    dialogMessage = "根据历史推荐（使用${recommendation.usageCount}次）",
                    lastAutofill = AutoFillSnapshot(
                        recommendation.roomName,
                        recommendation.seatNumber
                    )
                )
            )
        }
    }
}
```

#### 新增"根据历史创建"快捷按钮
在 `TaskListScreen.kt` 的空状态面板添加：
```kotlin
if (uiState.accounts.isNotEmpty() && uiState.plans.isEmpty()) {
    TextButton(
        onClick = viewModel::createTasksFromHistory,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("根据历史记录批量创建任务")
    }
}
```

---

## 五、断网状态优化

### 5.1 问题分析

从日志中可以看到：
```
后台 Wi-Fi 重连未开启
校园网认证冷却中，请稍后再试。
```

### 5.2 优化方案

#### 网络状态检测
```kotlin
// NetworkStateDetector.kt
class NetworkStateDetector(
    private val context: Context
) {
    fun isConnected(): Boolean {
        val cm = context.getSystemService<ConnectivityManager>()
        val network = cm?.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isOnCampusNetwork(): Boolean {
        val cm = context.getSystemService<ConnectivityManager>()
        val network = cm?.activeNetwork ?: return false
        val networkInfo = cm.getLinkProperties(network)
        // 检查是否连接到校园网SSID或IP段
        return networkInfo?.interfaceName?.contains("wlan") == true
    }
}
```

#### TaskListScreen 网络提示
```kotlin
// TaskListScreen.kt
if (!networkState.isConnected) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Outlined.WifiOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    "网络未连接",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Text(
                "自动签到需要网络连接，请检查网络设置",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { /* 打开网络设置 */ },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text("检查网络")
                }
                OutlinedButton(
                    onClick = viewModel::retryNetworkCheck,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text("重试")
                }
            }
        }
    }
}
```

---

## 六、文件清单

### 新增文件
1. `SmartSeatRecommender.kt` - 智能座位推荐器
2. `NetworkStateDetector.kt` - 网络状态检测器
3. `BatchOperationModels.kt` - 批量操作数据模型

### 修改文件
1. `SeatDisplayScreen.kt` - 添加一键签到/补约按钮
2. `SeatDisplayCard.kt` - 优化卡片UI
3. `SeatDisplayViewModel.kt` - 支持批量操作
4. `SeatDisplayRepository.kt` - 批量签到/补约逻辑
5. `TaskListScreen.kt` - 网络状态提示，历史创建按钮
6. `AutomationTaskViewModel.kt` - 增强智能推荐
7. `SeatDisplayUiState.kt` - 添加批量操作状态字段

---

## 七、测试计划

### 7.1 功能测试
- [ ] 一键签到：测试1个、多个、全部账号签到场景
- [ ] 一键补约：测试座位推荐准确性和预约成功率
- [ ] 智能推荐：验证推荐算法对不同历史记录的处理
- [ ] 断网提示：测试网络状态检测和UI提示

### 7.2 UI测试
- [ ] 验证UI反馈的即时性和明确性
- [ ] 检查信息展示的简洁性
- [ ] 测试不同屏幕尺寸的适配

### 7.3 异常测试
- [ ] 网络中断时的错误处理
- [ ] 账号未认证时的提示
- [ ] 座位已被占用时的反馈
- [ ] 并发操作的互斥处理

---

## 八、版本更新说明

**版本号**：3.2.0

**更新内容**：
1. 新增座位页面一键签到功能
2. 新增智能一键补约功能，基于历史记录自动推荐座位
3. 自动任务支持历史记录智能推荐
4. 优化网络断开状态的提示和操作选项
5. 改进UI反馈强度，简化页面信息展示
6. 提升整体交互体验

**注意事项**：
- 一键补约会为所有账号预约，请确认后再操作
- 智能推荐基于最近5-7条历史记录
- 建议在校园网环境下使用

---

## 九、实施步骤

### 阶段一：基础功能实现（优先）
1. ✅ 创建智能座位推荐器
2. ✅ 实现批量签到功能
3. ✅ 实现一键补约功能

### 阶段二：UI优化
4. 优化座位卡片展示
5. 添加操作反馈动画
6. 简化信息展示

### 阶段三：网络状态增强
7. 实现网络状态检测
8. 添加断网提示UI
9. 提供网络检查和重试选项

### 阶段四：测试和完善
10. 功能测试
11. UI/UX测试
12. 性能优化
13. 发布版本

---

**文档版本**：1.0
**创建日期**：2026-06-23
**最后更新**：2026-06-23
