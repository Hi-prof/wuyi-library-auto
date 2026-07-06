# Android Quality Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the Android regressions found in review: unreachable navigation, unsafe one-click makeup reservation, incorrect smart-seat tie breaking, lint blockers, and long-running action feedback.

**Architecture:** Keep the current Compose + ViewModel + repository structure. UI routing fixes stay in `ui/navigation` and `ui/screen/home`; reservation correctness stays in `ui/repository/seat` behind a small request/result boundary; API compatibility fixes stay in the settings and manifest files. Each task adds focused tests before code changes and ends with a runnable verification command.

**Tech Stack:** Kotlin 1.9.24, Android Gradle Plugin 8.5.2, Compose Material3 with Navigation Compose 2.7.7, Room 2.6.1, kotlinx-coroutines-test 1.8.1, JUnit 4.13.2, Truth 1.4.4, minSdk 26, targetSdk 34, compileSdk 34.

## Global Constraints

- Work under `library-android`; do not modify `library-fwq` or `library-window`.
- Preserve existing Android package names under `com.wuyi.libraryauto`.
- Keep Android `min-sdk = "26"`, `target-sdk = "34"`, and `compile-sdk = "34"`.
- Use existing dependencies from `library-android/gradle/libs.versions.toml`; do not add new libraries.
- Use Kotlin coroutines and existing repository interfaces; do not introduce a dependency injection rewrite.
- All user-visible Android copy remains Chinese.
- Avoid destructive git commands; commit each task independently.
- Before final handoff, run `.\gradlew.bat :app:compileDebugKotlin --console=plain`, `.\gradlew.bat :app:testDebugUnitTest --console=plain`, and `.\gradlew.bat :app:lintDebug --console=plain`.

---

## Scope Check

The review surfaced UI navigation, reservation business logic, recommendation ranking, API compatibility, and progress feedback. These are separate subsystems, but they all block the same Android release and can be delivered as independent task commits in one plan. If execution capacity is limited, implement Task 1, Task 2, and Task 5 first because they address user-facing broken flows and release-blocking lint errors.

## File Structure

- `library-android/app/src/main/java/com/wuyi/libraryauto/ui/navigation/AppNavGraph.kt`: Owns app routes and connects Home quick actions to existing screens.
- `library-android/app/src/main/java/com/wuyi/libraryauto/ui/screen/home/TodayOverviewScreen.kt`: Shows Home quick actions for accounts, manual reservation, seat status, tasks, and settings.
- `library-android/app/src/androidTest/java/com/wuyi/libraryauto/SmokeNavigationTest.kt`: Verifies launch screen and navigation entry points on device/emulator.
- `library-android/app/src/main/java/com/wuyi/libraryauto/ui/repository/seat/SmartSeatRecommender.kt`: Chooses the best historical seat; should prefer higher frequency, then most recent use.
- `library-android/app/src/test/java/com/wuyi/libraryauto/ui/repository/seat/SmartSeatRecommenderTest.kt`: Unit tests recommendation ranking.
- `library-android/app/src/main/java/com/wuyi/libraryauto/ui/repository/seat/BatchOperationModels.kt`: Adds per-step batch reservation status and skipped result semantics.
- `library-android/app/src/main/java/com/wuyi/libraryauto/ui/repository/seat/SeatDisplayRepository.kt`: Implements safe one-click makeup reservation using seat lookup data and duplicate-date checks.
- `library-android/app/src/test/java/com/wuyi/libraryauto/ui/repository/seat/SeatDisplayRepositoryBatchMakeupTest.kt`: Unit tests batch makeup reservation request construction and duplicate skipping.
- `library-android/app/src/main/java/com/wuyi/libraryauto/ui/viewmodel/SeatDisplayViewModel.kt`: Exposes batch progress state and catches repository failures.
- `library-android/app/src/main/java/com/wuyi/libraryauto/ui/screen/seat/SeatDisplayUiState.kt`: Carries batch progress text.
- `library-android/app/src/main/java/com/wuyi/libraryauto/ui/screen/seat/SeatDisplayScreen.kt`: Displays progress while batch operations run.
- `library-android/app/src/main/java/com/wuyi/libraryauto/ui/screen/settings/WifiReconnectSuggestionRegistrar.kt`: Guards API 29 Wi-Fi suggestion calls for minSdk 26 devices.
- `library-android/app/src/main/java/com/wuyi/libraryauto/ui/screen/settings/SignInMonitoringViewModel.kt`: Fixes suspicious indentation by making placeholder-state assignment explicit.
- `library-android/app/src/main/AndroidManifest.xml`: Adds coarse location permission for API 31+ lint compatibility.
- `library-android/app/src/test/java/com/wuyi/libraryauto/ui/screen/settings/WifiReconnectSuggestionRegistrarTest.kt`: Robolectric test for API 26 behavior and API 29 suggestion path.
- `library-android/app/src/test/java/com/wuyi/libraryauto/ui/screen/settings/SignInMonitoringViewModelTest.kt`: Extends existing tests to cover delayed placeholder loading.

---

### Task 1: Restore Home Navigation Entry Points

**Files:**
- Modify: `library-android/app/src/main/java/com/wuyi/libraryauto/ui/navigation/AppNavGraph.kt`
- Modify: `library-android/app/src/main/java/com/wuyi/libraryauto/ui/screen/home/TodayOverviewScreen.kt`
- Test: `library-android/app/src/androidTest/java/com/wuyi/libraryauto/SmokeNavigationTest.kt`

**Interfaces:**
- Consumes: Existing routes `AppRoutes.SeatLookup`, `AppRoutes.SeatDisplay`, and `AppRoutes.Tasks`.
- Produces: `TodayOverviewScreen(..., onOpenManualReservation: () -> Unit, onOpenSeatDisplay: () -> Unit, onOpenTasks: () -> Unit)` so Home can navigate to all primary flows.

- [ ] **Step 1: Write the failing smoke navigation test**

Replace `SmokeNavigationTest` with this test class:

```kotlin
package com.wuyi.libraryauto

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class SmokeNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesIntoTodayOverviewScreenAndPrimaryDestinationsAreReachable() {
        composeRule.onNodeWithText("首页").assertIsDisplayed()
        composeRule.onNodeWithText("今日概览").assertIsDisplayed()
        composeRule.onNodeWithText("总账号数").assertIsDisplayed()
        composeRule.onNodeWithText("已预约座位").assertIsDisplayed()
        composeRule.onNodeWithText("一键检查预约").assertIsDisplayed()
        composeRule.onNodeWithText("一键签到").assertIsDisplayed()
        composeRule.onNodeWithText("账号管理").assertIsDisplayed()
        composeRule.onNodeWithText("手动预约").assertIsDisplayed()
        composeRule.onNodeWithText("座位状态").assertIsDisplayed()
        composeRule.onNodeWithText("自动任务").assertIsDisplayed()

        composeRule.onNodeWithText("手动预约").performClick()
        composeRule.onNodeWithText("选择账号").assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        composeRule.onNodeWithText("座位状态").performClick()
        composeRule.onNodeWithText("暂无账号座位状态").assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        composeRule.onNodeWithText("自动任务").performClick()
        composeRule.onNodeWithText("还没有可用账号").assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        composeRule.onNodeWithContentDescription("打开设置").performClick()
        composeRule.onNodeWithText("设置").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:connectedDebugAndroidTest --console=plain -Pandroid.testInstrumentationRunnerArguments.class=com.wuyi.libraryauto.SmokeNavigationTest`

Expected: FAIL because Home does not render `手动预约`, `座位状态`, and `自动任务` quick actions.

- [ ] **Step 3: Extend Home screen API and quick actions**

In `TodayOverviewScreen`, change the function signature to:

```kotlin
fun TodayOverviewScreen(
    repository: TodayOverviewRepository,
    seatDisplayRepository: SeatDisplayRepository,
    onOpenAccountManager: () -> Unit,
    onOpenAddAccount: () -> Unit,
    onOpenManualReservation: () -> Unit,
    onOpenSeatDisplay: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenSettings: () -> Unit,
)
```

Pass the new callbacks into `OverviewCard`:

```kotlin
OverviewCard(
    snapshot = snapshot,
    errorMessage = uiState.errorMessage,
    actionMessage = uiState.actionMessage,
    isCheckingReservations = uiState.isCheckingReservations,
    isSigningIn = uiState.isSigningIn,
    onCheckReservations = viewModel::checkReservations,
    onCheckInAll = viewModel::checkInAll,
    onOpenAccountManager = onOpenAccountManager,
    onOpenAddAccount = onOpenAddAccount,
    onOpenManualReservation = onOpenManualReservation,
    onOpenSeatDisplay = onOpenSeatDisplay,
    onOpenTasks = onOpenTasks,
)
```

Change `OverviewCard` signature to include:

```kotlin
private fun OverviewCard(
    snapshot: TodayOverviewSnapshot,
    errorMessage: String,
    actionMessage: String,
    isCheckingReservations: Boolean,
    isSigningIn: Boolean,
    onCheckReservations: () -> Unit,
    onCheckInAll: () -> Unit,
    onOpenAccountManager: () -> Unit,
    onOpenAddAccount: () -> Unit,
    onOpenManualReservation: () -> Unit,
    onOpenSeatDisplay: () -> Unit,
    onOpenTasks: () -> Unit,
)
```

Replace the bottom two-button row in `OverviewCard` with this two-row quick-action block:

```kotlin
Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalButton(
            onClick = onOpenAccountManager,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Icons.Outlined.ManageAccounts,
                contentDescription = null,
            )
            Text("账号管理", modifier = Modifier.padding(start = 8.dp))
        }
        Button(
            onClick = onOpenAddAccount,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Icons.Outlined.PersonAdd,
                contentDescription = null,
            )
            Text("添加账号", modifier = Modifier.padding(start = 8.dp))
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalButton(
            onClick = onOpenManualReservation,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Icons.Outlined.EventSeat,
                contentDescription = null,
            )
            Text("手动预约", modifier = Modifier.padding(start = 8.dp))
        }
        FilledTonalButton(
            onClick = onOpenSeatDisplay,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Icons.Outlined.DoneAll,
                contentDescription = null,
            )
            Text("座位状态", modifier = Modifier.padding(start = 8.dp))
        }
    }
    FilledTonalButton(
        onClick = onOpenTasks,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = Icons.Outlined.Refresh,
            contentDescription = null,
        )
        Text("自动任务", modifier = Modifier.padding(start = 8.dp))
    }
}
```

- [ ] **Step 4: Wire callbacks in AppNavGraph**

In the `TodayOverviewScreen` call inside `AppNavGraph`, use this callback set:

```kotlin
TodayOverviewScreen(
    repository = appDependencies.todayOverviewRepository,
    seatDisplayRepository = appDependencies.seatDisplayRepository,
    onOpenAccountManager = { navController.navigate(AppRoutes.Accounts) },
    onOpenAddAccount = { navController.navigate(AppRoutes.Login) },
    onOpenManualReservation = { navController.navigate(AppRoutes.SeatLookup) },
    onOpenSeatDisplay = { navController.navigate(AppRoutes.SeatDisplay) },
    onOpenTasks = { navController.navigate(AppRoutes.Tasks) },
    onOpenSettings = { navController.navigate(AppRoutes.Settings) },
)
```

- [ ] **Step 5: Run test to verify it passes**

Run: `.\gradlew.bat :app:connectedDebugAndroidTest --console=plain -Pandroid.testInstrumentationRunnerArguments.class=com.wuyi.libraryauto.SmokeNavigationTest`

Expected: PASS on an emulator or connected device.

- [ ] **Step 6: Run compile check**

Run: `.\gradlew.bat :app:compileDebugKotlin --console=plain`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add library-android/app/src/main/java/com/wuyi/libraryauto/ui/navigation/AppNavGraph.kt library-android/app/src/main/java/com/wuyi/libraryauto/ui/screen/home/TodayOverviewScreen.kt library-android/app/src/androidTest/java/com/wuyi/libraryauto/SmokeNavigationTest.kt
git commit -m "fix(android): restore home navigation entries"
```

---

### Task 2: Fix Smart Seat Recommendation Tie Breaking

**Files:**
- Modify: `library-android/app/src/main/java/com/wuyi/libraryauto/ui/repository/seat/SmartSeatRecommender.kt`
- Create: `library-android/app/src/test/java/com/wuyi/libraryauto/ui/repository/seat/SmartSeatRecommenderTest.kt`

**Interfaces:**
- Consumes: `SmartSeatRecommender.analyzeHistory(history: List<ReservationHistoryHit>): SeatRecommendation?`
- Produces: Recommendation ranking that sorts by highest usage count, then latest `timestampEpochSeconds`.

- [ ] **Step 1: Write the failing recommendation tests**

Create `SmartSeatRecommenderTest.kt`:

```kotlin
package com.wuyi.libraryauto.ui.repository.seat

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.repository.task.HistorySource
import com.wuyi.libraryauto.ui.repository.task.ReservationHistoryHit
import org.junit.Test

class SmartSeatRecommenderTest {

    @Test
    fun analyzeHistory_prefersMostFrequentSeat() {
        val recommendation =
            SmartSeatRecommender.analyzeHistory(
                listOf(
                    hit(roomName = "A区", seatNumber = "001", timestamp = 100),
                    hit(roomName = "B区", seatNumber = "009", timestamp = 200),
                    hit(roomName = "B区", seatNumber = "009", timestamp = 300),
                ),
            )

        assertThat(recommendation?.roomName).isEqualTo("B区")
        assertThat(recommendation?.seatNumber).isEqualTo("009")
        assertThat(recommendation?.usageCount).isEqualTo(2)
        assertThat(recommendation?.latestUsedTimestamp).isEqualTo(300)
    }

    @Test
    fun analyzeHistory_tieBreaksByLatestUse() {
        val recommendation =
            SmartSeatRecommender.analyzeHistory(
                listOf(
                    hit(roomName = "旧区", seatNumber = "010", timestamp = 100),
                    hit(roomName = "新区", seatNumber = "020", timestamp = 900),
                ),
            )

        assertThat(recommendation?.roomName).isEqualTo("新区")
        assertThat(recommendation?.seatNumber).isEqualTo("020")
        assertThat(recommendation?.usageCount).isEqualTo(1)
        assertThat(recommendation?.latestUsedTimestamp).isEqualTo(900)
    }

    private fun hit(
        roomName: String,
        seatNumber: String,
        timestamp: Long,
    ): ReservationHistoryHit =
        ReservationHistoryHit(
            studentId = "20230001",
            roomName = roomName,
            seatNumber = seatNumber,
            timestampEpochSeconds = timestamp,
            source = HistorySource.RESERVATION_TASK,
        )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --console=plain --tests com.wuyi.libraryauto.ui.repository.seat.SmartSeatRecommenderTest`

Expected: FAIL in `analyzeHistory_tieBreaksByLatestUse`; current code chooses the older tied record.

- [ ] **Step 3: Fix ranking**

Replace the `maxWithOrNull` comparator in `SmartSeatRecommender.analyzeHistory` with:

```kotlin
val (roomSeat, freq) = seatFrequency.maxWithOrNull(
    compareBy<Map.Entry<Pair<String, String>, SeatFrequency>> { it.value.count }
        .thenBy { it.value.latestTimestamp },
) ?: return null
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --console=plain --tests com.wuyi.libraryauto.ui.repository.seat.SmartSeatRecommenderTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add library-android/app/src/main/java/com/wuyi/libraryauto/ui/repository/seat/SmartSeatRecommender.kt library-android/app/src/test/java/com/wuyi/libraryauto/ui/repository/seat/SmartSeatRecommenderTest.kt
git commit -m "fix(android): prefer latest smart seat recommendation ties"
```

---

### Task 3: Make One-Click Makeup Reservation Safe

**Files:**
- Modify: `library-android/app/src/main/java/com/wuyi/libraryauto/ui/repository/seat/BatchOperationModels.kt`
- Modify: `library-android/app/src/main/java/com/wuyi/libraryauto/ui/repository/seat/SeatDisplayRepository.kt`
- Test: `library-android/app/src/test/java/com/wuyi/libraryauto/ui/repository/seat/SeatDisplayRepositoryBatchMakeupTest.kt`

**Interfaces:**
- Consumes: `SeatDisplayRepository.batchMakeupReservation(): BatchReservationResult`, `SmartSeatRecommender.recommendSeatForSingleAccount(studentId: String): SeatRecommendation?`, and `SeatLookupRepository.loadSeats(query: SeatLookupQuery): SeatLookupLoadResult` after this task injects a lookup repository.
- Produces: Batch reservation only calls `ManualReservationGateway.reserve(selection)` when a target date has no existing active booking and the target room/seat can be resolved to a real `roomId`.

- [ ] **Step 1: Extend batch result model**

In `BatchOperationModels.kt`, replace `ReservationResult` with:

```kotlin
data class ReservationResult(
    val studentId: String,
    val targetDate: LocalDate,
    val success: Boolean,
    val message: String,
    val bookingId: String? = null,
    val error: String? = null,
    val skipped: Boolean = false,
)
```

Replace `BatchReservationResult.fromResults` with:

```kotlin
fun fromResults(results: List<ReservationResult>): BatchReservationResult {
    val actionable = results.filterNot { it.skipped }
    return BatchReservationResult(
        total = results.size,
        success = actionable.count { it.success },
        failed = actionable.count { !it.success },
        details = results,
    )
}
```

- [ ] **Step 2: Write failing batch makeup tests**

Create `SeatDisplayRepositoryBatchMakeupTest.kt`:

```kotlin
package com.wuyi.libraryauto.ui.repository.seat

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import com.wuyi.libraryauto.core.network.seat.SeatBookingLiveState
import com.wuyi.libraryauto.core.storage.db.ReservationTaskDao
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import com.wuyi.libraryauto.core.storage.db.SeatDisplaySnapshotDao
import com.wuyi.libraryauto.core.storage.db.SeatDisplaySnapshotEntity
import com.wuyi.libraryauto.ui.repository.session.SessionRepository
import com.wuyi.libraryauto.ui.repository.task.AccountSeatAction
import com.wuyi.libraryauto.ui.repository.task.AccountSeatActionExecutionResult
import com.wuyi.libraryauto.ui.repository.task.AccountSeatActionExecutor
import com.wuyi.libraryauto.ui.repository.task.AccountReservationHistoryReader
import com.wuyi.libraryauto.ui.repository.task.HistorySource
import com.wuyi.libraryauto.ui.repository.task.ReservationHistoryHit
import com.wuyi.libraryauto.ui.repository.task.SeatBookingSnapshotView
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountEntry
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SeatDisplayRepositoryBatchMakeupTest {

    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 7, 4)

    @Test
    fun batchMakeupReservation_usesResolvedRoomIdAndEntryUrlForRecommendedSeat() = runTest {
        val gateway = RecordingManualReservationGateway()
        val repository =
            buildRepository(
                manualReservationGateway = gateway,
                seatLookupRepository =
                    FakeSeatLookupRepository(
                        SeatLookupLoadResult.Success(
                            SeatLookupData(
                                beginTimeEpochSeconds = null,
                                durationHours = null,
                                peopleCount = 1,
                                rooms =
                                    listOf(
                                        SeatRoomSnapshot(
                                            roomId = "room-a",
                                            roomName = "自习室A",
                                            storey = "2F",
                                            availableCount = 1,
                                            seatNumbers = listOf("101"),
                                            recommendedSeatNumber = "101",
                                        ),
                                    ),
                            ),
                        ),
                    ),
            )

        val result = repository.batchMakeupReservation()

        assertThat(result.failed).isEqualTo(0)
        assertThat(result.success).isEqualTo(3)
        assertThat(gateway.selections).hasSize(3)
        assertThat(gateway.selections.map { it.entryUrl }.distinct()).containsExactly(DEFAULT_ENTRY_URL)
        assertThat(gateway.selections.map { it.roomId }.distinct()).containsExactly("room-a")
        assertThat(gateway.selections.map { it.roomName }.distinct()).containsExactly("自习室A")
        assertThat(gateway.selections.map { it.seatNumber }.distinct()).containsExactly("101")
    }

    @Test
    fun batchMakeupReservation_skipsDatesThatAlreadyHaveActiveBookings() = runTest {
        val gateway = RecordingManualReservationGateway()
        val repository =
            buildRepository(
                manualReservationGateway = gateway,
                accountSeatActionExecutor =
                    FakeAccountSeatActionExecutor(
                        activeBookings =
                            listOf(
                                SeatBookingSnapshotView(
                                    roomName = "自习室A",
                                    seatNumber = "101",
                                    beginLabel = "2026-07-04 08:00",
                                    statusLabel = "待签到",
                                    liveState = SeatBookingLiveState.RESERVED_WAITING_SIGNIN,
                                ),
                            ),
                    ),
                seatLookupRepository =
                    FakeSeatLookupRepository(
                        SeatLookupLoadResult.Success(
                            SeatLookupData(
                                beginTimeEpochSeconds = null,
                                durationHours = null,
                                peopleCount = 1,
                                rooms =
                                    listOf(
                                        SeatRoomSnapshot(
                                            roomId = "room-a",
                                            roomName = "自习室A",
                                            storey = "2F",
                                            availableCount = 1,
                                            seatNumbers = listOf("101"),
                                            recommendedSeatNumber = "101",
                                        ),
                                    ),
                            ),
                        ),
                    ),
            )

        val result = repository.batchMakeupReservation()

        assertThat(result.details.any { it.skipped && it.targetDate == today }).isTrue()
        assertThat(gateway.selections).hasSize(2)
        assertThat(gateway.selections.map { it.beginTimeEpochSeconds.toLocalDate() })
            .containsExactly(today.plusDays(1), today.plusDays(2))
            .inOrder()
    }

    private fun buildRepository(
        manualReservationGateway: RecordingManualReservationGateway,
        accountSeatActionExecutor: AccountSeatActionExecutor = FakeAccountSeatActionExecutor(),
        seatLookupRepository: SeatLookupRepository,
    ): SeatDisplayRepository =
        SeatDisplayRepository(
            accountRepository = FakeSavedAccountRepository(),
            sessionRepository = FakeSessionRepository(),
            reservationTaskDao = FakeReservationTaskDao(),
            seatDisplaySnapshotDao = FakeSeatDisplaySnapshotDao(),
            accountSeatActionExecutor = accountSeatActionExecutor,
            smartSeatRecommender =
                SmartSeatRecommender(
                    FakeHistoryReader(
                        listOf(
                            ReservationHistoryHit(
                                studentId = "20230001",
                                roomName = "自习室A",
                                seatNumber = "101",
                                timestampEpochSeconds = 1_700_000_000,
                                source = HistorySource.RESERVATION_TASK,
                            ),
                        ),
                    ),
                ),
            manualReservationGateway = manualReservationGateway,
            seatLookupRepository = seatLookupRepository,
            clockMillis = { today.atStartOfDay(zoneId).toInstant().toEpochMilli() },
        )

    private fun Int.toLocalDate(): LocalDate =
        java.time.Instant.ofEpochSecond(toLong()).atZone(zoneId).toLocalDate()

    private class RecordingManualReservationGateway : ManualReservationGateway {
        val selections = mutableListOf<ManualReservationSelection>()

        override suspend fun reserve(selection: ManualReservationSelection): ManualReservationResult {
            selections += selection
            return ManualReservationResult.Success(
                taskId = "task-${selections.size}",
                bookingId = "booking-${selections.size}",
                message = "预约成功",
            )
        }
    }

    private class FakeSeatLookupRepository(
        private val result: SeatLookupLoadResult,
    ) : SeatLookupRepository {
        override suspend fun loadDefaultSeats(): SeatLookupLoadResult = result
        override suspend fun loadDefaultSeats(studentId: String): SeatLookupLoadResult = result
        override suspend fun loadSeats(query: SeatLookupQuery): SeatLookupLoadResult = result
    }

    private class FakeHistoryReader(
        private val history: List<ReservationHistoryHit>,
    ) : AccountReservationHistoryReader {
        override suspend fun loadHistory(studentId: String): List<ReservationHistoryHit> = history
    }

    private class FakeSavedAccountRepository : SavedAccountRepository {
        override fun readAll(): List<SavedAccountEntry> =
            listOf(SavedAccountEntry(studentId = "20230001", password = "secret"))

        override fun remove(studentId: String) = Unit
    }

    private class FakeSessionRepository : SessionRepository {
        override val session: kotlinx.coroutines.flow.StateFlow<com.wuyi.libraryauto.core.network.auth.AuthenticatedSession?> =
            kotlinx.coroutines.flow.MutableStateFlow(null)

        override fun currentSession(studentId: String): com.wuyi.libraryauto.core.network.auth.AuthenticatedSession? = null
        override fun activeStudentId(): String? = null
        override suspend fun save(session: com.wuyi.libraryauto.core.network.auth.AuthenticatedSession) = Unit
        override suspend fun clear() = Unit
        override suspend fun clear(studentId: String) = Unit
    }

    private class FakeReservationTaskDao : ReservationTaskDao {
        override suspend fun upsert(task: ReservationTaskEntity) = Unit
        override suspend fun findById(id: String): ReservationTaskEntity? = null
        override suspend fun findLatestForStudent(studentId: String): ReservationTaskEntity? = null
        override suspend fun listForStudent(studentId: String): List<ReservationTaskEntity> = emptyList()
        override fun observeAll(): Flow<List<ReservationTaskEntity>> = flowOf(emptyList())
        override suspend fun listAll(): List<ReservationTaskEntity> = emptyList()
    }

    private class FakeSeatDisplaySnapshotDao : SeatDisplaySnapshotDao {
        override suspend fun upsert(snapshot: SeatDisplaySnapshotEntity) = Unit
        override suspend fun findByStudentId(studentId: String): SeatDisplaySnapshotEntity? = null
        override suspend fun listAll(): List<SeatDisplaySnapshotEntity> = emptyList()
    }

    private class FakeAccountSeatActionExecutor(
        private val activeBookings: List<SeatBookingSnapshotView> = emptyList(),
    ) : AccountSeatActionExecutor {
        override suspend fun loadSnapshot(studentId: String): SeatBookingSnapshotView =
            SeatBookingSnapshotView(liveState = SeatBookingLiveState.IDLE)

        override suspend fun loadActiveBookings(studentId: String): List<SeatBookingSnapshotView> = activeBookings

        override suspend fun performAction(
            studentId: String,
            action: AccountSeatAction,
        ): AccountSeatActionExecutionResult =
            AccountSeatActionExecutionResult(
                studentId = studentId,
                action = action,
                updatedSnapshot = loadSnapshot(studentId),
                message = "ok",
            )
    }

    private companion object {
        const val DEFAULT_ENTRY_URL = "https://wechat.v2.traceint.com/index.php/reserve/index.html"
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --console=plain --tests com.wuyi.libraryauto.ui.repository.seat.SeatDisplayRepositoryBatchMakeupTest`

Expected: FAIL because `SeatDisplayRepository` does not accept `seatLookupRepository`, still sends blank `entryUrl` and `roomId`, and does not skip already-booked dates.

- [ ] **Step 4: Inject seat lookup repository into SeatDisplayRepository**

Change the constructor in `SeatDisplayRepository` to include a nullable lookup dependency:

```kotlin
class SeatDisplayRepository(
    private val accountRepository: SavedAccountRepository,
    private val sessionRepository: SessionRepository,
    private val reservationTaskDao: ReservationTaskDao,
    private val seatDisplaySnapshotDao: SeatDisplaySnapshotDao,
    private val accountSeatActionExecutor: AccountSeatActionExecutor?,
    private val smartSeatRecommender: SmartSeatRecommender?,
    private val manualReservationGateway: ManualReservationGateway?,
    private val seatLookupRepository: SeatLookupRepository? = null,
    private val clockMillis: () -> Long = System::currentTimeMillis,
)
```

In `AppDependencies`, pass the existing repository:

```kotlin
seatLookupRepository = seatLookupRepository,
```

- [ ] **Step 5: Replace batch makeup internals with safe lookup and duplicate skip**

Add these private helpers to `SeatDisplayRepository`:

```kotlin
private data class BatchReservationTarget(
    val studentId: String,
    val targetDate: java.time.LocalDate,
    val recommendation: SeatRecommendation,
    val beginTimeEpochSeconds: Int,
)

private fun buildTargetDates(): List<java.time.LocalDate> {
    val today =
        java.time.Instant
            .ofEpochMilli(clockMillis())
            .atZone(SHANGHAI_ZONE)
            .toLocalDate()
    return listOf(today, today.plusDays(1), today.plusDays(2))
}

private fun java.time.LocalDate.toBatchBeginEpochSeconds(): Int =
    atTime(8, 0).atZone(SHANGHAI_ZONE).toEpochSecond().toInt()

private fun SeatBookingSnapshotView.matchesDate(targetDate: java.time.LocalDate): Boolean =
    beginLabel.contains(targetDate.toString()) ||
        beginLabel.contains(targetDate.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd")))

private suspend fun hasActiveBookingOnDate(
    studentId: String,
    targetDate: java.time.LocalDate,
): Boolean {
    val executor = accountSeatActionExecutor ?: return false
    return runCatching { executor.loadActiveBookings(studentId) }
        .getOrDefault(emptyList())
        .any { booking -> booking.matchesDate(targetDate) }
}

private suspend fun resolveReservationSelection(target: BatchReservationTarget): ManualReservationSelection? {
    val lookup = seatLookupRepository ?: return null
    val entryUrl = SchoolPortalConfig.SeatEntryUrls.firstOrNull().orEmpty()
    if (entryUrl.isBlank()) return null
    val result =
        lookup.loadSeats(
            SeatLookupQuery(
                studentId = target.studentId,
                entryUrl = entryUrl,
                beginTimeEpochSeconds = target.beginTimeEpochSeconds,
                durationSeconds = 12 * 3600,
                peopleCount = 1,
            ),
        )
    val data =
        when (result) {
            is SeatLookupLoadResult.Success -> result.data
            is SeatLookupLoadResult.Empty -> result.data
            is SeatLookupLoadResult.Failure,
            SeatLookupLoadResult.NotLoggedIn,
            -> return null
        }
    val room =
        data.rooms.firstOrNull { room ->
            room.roomName.trim() == target.recommendation.roomName.trim() &&
                room.seatNumbers.any { seat -> seat.trim() == target.recommendation.seatNumber.trim() }
        } ?: return null
    return ManualReservationSelection(
        studentId = target.studentId,
        entryUrl = entryUrl,
        roomId = room.roomId,
        roomName = room.roomName,
        seatNumber = target.recommendation.seatNumber,
        beginTimeEpochSeconds = target.beginTimeEpochSeconds,
        durationSeconds = 12 * 3600,
        peopleCount = 1,
    )
}
```

Replace `batchMakeupReservation()` with:

```kotlin
suspend fun batchMakeupReservation(): BatchReservationResult {
    val recommender = smartSeatRecommender
        ?: return BatchReservationResult.fromResults(emptyList())
    val gateway = manualReservationGateway
        ?: return BatchReservationResult.fromResults(emptyList())

    val allCards = readCachedFromLocal()
    if (allCards.isEmpty()) {
        return BatchReservationResult.fromResults(emptyList())
    }

    val targetDates = buildTargetDates()
    val results = mutableListOf<ReservationResult>()

    for (card in allCards) {
        val recommendation = recommender.recommendSeatForSingleAccount(card.studentId)
        if (recommendation == null) {
            results.add(
                ReservationResult(
                    studentId = card.studentId,
                    targetDate = targetDates.first(),
                    success = false,
                    message = "无历史记录，无法推荐座位",
                    error = "无历史记录",
                ),
            )
            continue
        }

        for (targetDate in targetDates) {
            if (hasActiveBookingOnDate(card.studentId, targetDate)) {
                results.add(
                    ReservationResult(
                        studentId = card.studentId,
                        targetDate = targetDate,
                        success = false,
                        message = "已有活跃预约，跳过补约",
                        skipped = true,
                    ),
                )
                continue
            }

            val target =
                BatchReservationTarget(
                    studentId = card.studentId,
                    targetDate = targetDate,
                    recommendation = recommendation,
                    beginTimeEpochSeconds = targetDate.toBatchBeginEpochSeconds(),
                )
            val selection = resolveReservationSelection(target)
            if (selection == null) {
                results.add(
                    ReservationResult(
                        studentId = card.studentId,
                        targetDate = targetDate,
                        success = false,
                        message = "推荐座位当前不可预约",
                        error = "未找到可预约的目标房间或座位",
                    ),
                )
                continue
            }

            val result = gateway.reserve(selection)
            results.add(
                when (result) {
                    is ManualReservationResult.Success ->
                        ReservationResult(
                            studentId = card.studentId,
                            targetDate = targetDate,
                            success = true,
                            message = result.message,
                            bookingId = result.bookingId,
                        )
                    is ManualReservationResult.Failure ->
                        ReservationResult(
                            studentId = card.studentId,
                            targetDate = targetDate,
                            success = false,
                            message = result.message,
                            error = result.message,
                        )
                    is ManualReservationResult.NotLoggedIn ->
                        ReservationResult(
                            studentId = card.studentId,
                            targetDate = targetDate,
                            success = false,
                            message = "需要登录",
                            error = "需要登录",
                        )
                },
            )
        }
    }

    return BatchReservationResult.fromResults(results)
}
```

Add this import:

```kotlin
import com.wuyi.libraryauto.ui.repository.SchoolPortalConfig
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --console=plain --tests com.wuyi.libraryauto.ui.repository.seat.SeatDisplayRepositoryBatchMakeupTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Run related tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --console=plain --tests com.wuyi.libraryauto.ui.screen.seat.SeatDisplayRoomPresentationTest --tests com.wuyi.libraryauto.ui.repository.seat.SmartSeatRecommenderTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add library-android/app/src/main/java/com/wuyi/libraryauto/ui/repository/seat/BatchOperationModels.kt library-android/app/src/main/java/com/wuyi/libraryauto/ui/repository/seat/SeatDisplayRepository.kt library-android/app/src/main/java/com/wuyi/libraryauto/ui/navigation/AppDependencies.kt library-android/app/src/test/java/com/wuyi/libraryauto/ui/repository/seat/SeatDisplayRepositoryBatchMakeupTest.kt
git commit -m "fix(android): resolve safe batch makeup reservations"
```

---

### Task 4: Add Batch Operation Progress and Failure Feedback

**Files:**
- Modify: `library-android/app/src/main/java/com/wuyi/libraryauto/ui/screen/seat/SeatDisplayUiState.kt`
- Modify: `library-android/app/src/main/java/com/wuyi/libraryauto/ui/viewmodel/SeatDisplayViewModel.kt`
- Modify: `library-android/app/src/main/java/com/wuyi/libraryauto/ui/screen/seat/SeatDisplayScreen.kt`
- Test: `library-android/app/src/test/java/com/wuyi/libraryauto/ui/viewmodel/SeatDisplayViewModelTest.kt`

**Interfaces:**
- Consumes: Existing `SeatDisplayViewModel.batchCheckIn()` and `SeatDisplayViewModel.batchMakeupReservation()`.
- Produces: `SeatDisplayUiState.batchProgressMessage: String` and `SeatDisplayUiState.batchErrorMessage: String` for UI feedback.

- [ ] **Step 1: Extend UI state**

In `SeatDisplayUiState.kt`, add two fields:

```kotlin
data class SeatDisplayUiState(
    val cards: List<SeatDisplayCardUiState> = emptyList(),
    val isRefreshingAll: Boolean = false,
    val singleRefreshing: Set<String> = emptySet(),
    val emptyHint: String = "",
    val isBatchCheckingIn: Boolean = false,
    val lastBatchCheckInResult: BatchCheckInResult? = null,
    val isBatchReserving: Boolean = false,
    val lastBatchReservationResult: BatchReservationResult? = null,
    val batchProgressMessage: String = "",
    val batchErrorMessage: String = "",
)
```

- [ ] **Step 2: Write failing ViewModel tests**

Append these tests to `SeatDisplayViewModelTest.kt`:

```kotlin
@Test
fun batchMakeupReservation_showsProgressAndResult() = runTest {
    val repository =
        FakeSeatDisplayRepository(
            batchReservationResult =
                BatchReservationResult(
                    total = 3,
                    success = 2,
                    failed = 1,
                    details = emptyList(),
                ),
        )
    val viewModel = SeatDisplayViewModel(repository = repository, ioDispatcher = StandardTestDispatcher(testScheduler))

    viewModel.batchMakeupReservation()
    assertThat(viewModel.uiState.value.isBatchReserving).isTrue()
    assertThat(viewModel.uiState.value.batchProgressMessage).isEqualTo("正在补约今天和未来 2 天")

    advanceUntilIdle()

    assertThat(viewModel.uiState.value.isBatchReserving).isFalse()
    assertThat(viewModel.uiState.value.batchProgressMessage).isEmpty()
    assertThat(viewModel.uiState.value.lastBatchReservationResult?.success).isEqualTo(2)
}

@Test
fun batchMakeupReservation_reportsFailureMessage() = runTest {
    val repository =
        FakeSeatDisplayRepository(
            batchReservationFailure = IllegalStateException("预约接口不可用"),
        )
    val viewModel = SeatDisplayViewModel(repository = repository, ioDispatcher = StandardTestDispatcher(testScheduler))

    viewModel.batchMakeupReservation()
    advanceUntilIdle()

    assertThat(viewModel.uiState.value.isBatchReserving).isFalse()
    assertThat(viewModel.uiState.value.batchErrorMessage).isEqualTo("预约接口不可用")
}
```

If `SeatDisplayViewModelTest.kt` does not exist yet, create it with imports:

```kotlin
package com.wuyi.libraryauto.ui.viewmodel

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.repository.seat.BatchReservationResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeatDisplayViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --console=plain --tests com.wuyi.libraryauto.ui.viewmodel.SeatDisplayViewModelTest`

Expected: FAIL because progress and error state are not implemented.

- [ ] **Step 4: Update ViewModel batch methods**

In `SeatDisplayViewModel.batchCheckIn()`, change the first state update to:

```kotlin
_uiState.update {
    it.copy(
        isBatchCheckingIn = true,
        lastBatchCheckInResult = null,
        batchProgressMessage = "正在为待签到账号执行签到",
        batchErrorMessage = "",
    )
}
```

Wrap the operation with `runCatching`:

```kotlin
val result = runCatching { withContext(ioDispatcher) { repository.batchCheckIn() } }
result
    .onSuccess { batchResult ->
        _uiState.update { it.copy(lastBatchCheckInResult = batchResult) }
        loadInitialSnapshot()
    }
    .onFailure { error ->
        _uiState.update {
            it.copy(batchErrorMessage = error.message?.takeIf(String::isNotBlank) ?: "一键签到失败")
        }
    }
_uiState.update { it.copy(isBatchCheckingIn = false, batchProgressMessage = "") }
```

In `SeatDisplayViewModel.batchMakeupReservation()`, use:

```kotlin
_uiState.update {
    it.copy(
        isBatchReserving = true,
        lastBatchReservationResult = null,
        batchProgressMessage = "正在补约今天和未来 2 天",
        batchErrorMessage = "",
    )
}
val result =
    runCatching {
        withContext(ioDispatcher) { repository.batchMakeupReservation() }
    }
result
    .onSuccess { batchResult ->
        _uiState.update { it.copy(lastBatchReservationResult = batchResult) }
        loadInitialSnapshot()
    }
    .onFailure { error ->
        _uiState.update {
            it.copy(batchErrorMessage = error.message?.takeIf(String::isNotBlank) ?: "一键补约失败")
        }
    }
_uiState.update { it.copy(isBatchReserving = false, batchProgressMessage = "") }
```

- [ ] **Step 5: Display progress and errors in SeatDisplayScreen**

After `SeatDisplayStatsHeader(...)` item in `SeatDisplayScreen`, add:

```kotlin
if (uiState.batchProgressMessage.isNotBlank()) {
    item {
        BatchStatusBanner(
            message = uiState.batchProgressMessage,
            isError = false,
        )
    }
}
if (uiState.batchErrorMessage.isNotBlank()) {
    item {
        BatchStatusBanner(
            message = uiState.batchErrorMessage,
            isError = true,
        )
    }
}
```

Add this composable near the dialog composables:

```kotlin
@Composable
private fun BatchStatusBanner(
    message: String,
    isError: Boolean,
) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --console=plain --tests com.wuyi.libraryauto.ui.viewmodel.SeatDisplayViewModelTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Compile**

Run: `.\gradlew.bat :app:compileDebugKotlin --console=plain`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add library-android/app/src/main/java/com/wuyi/libraryauto/ui/screen/seat/SeatDisplayUiState.kt library-android/app/src/main/java/com/wuyi/libraryauto/ui/viewmodel/SeatDisplayViewModel.kt library-android/app/src/main/java/com/wuyi/libraryauto/ui/screen/seat/SeatDisplayScreen.kt library-android/app/src/test/java/com/wuyi/libraryauto/ui/viewmodel/SeatDisplayViewModelTest.kt
git commit -m "fix(android): show batch reservation progress"
```

---

### Task 5: Fix Lint API Compatibility Errors

**Files:**
- Modify: `library-android/app/src/main/java/com/wuyi/libraryauto/ui/screen/settings/WifiReconnectSuggestionRegistrar.kt`
- Modify: `library-android/app/src/main/java/com/wuyi/libraryauto/ui/screen/settings/SignInMonitoringViewModel.kt`
- Modify: `library-android/app/src/main/AndroidManifest.xml`
- Test: `library-android/app/src/test/java/com/wuyi/libraryauto/ui/screen/settings/WifiReconnectSuggestionRegistrarTest.kt`
- Test: `library-android/app/src/test/java/com/wuyi/libraryauto/ui/screen/settings/SignInMonitoringViewModelTest.kt`

**Interfaces:**
- Consumes: `WifiReconnectSuggestionRegistrar.syncSuggestions(previousSnapshot, currentSnapshot): String`.
- Produces: API 26-safe Wi-Fi suggestion sync, explicit sign-in placeholder state, and a manifest that satisfies `CoarseFineLocation`.

- [ ] **Step 1: Write Wi-Fi API compatibility test**

Create `WifiReconnectSuggestionRegistrarTest.kt`:

```kotlin
package com.wuyi.libraryauto.ui.screen.settings

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.storage.network.WifiReconnectNetwork
import com.wuyi.libraryauto.core.storage.network.WifiReconnectSnapshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class WifiReconnectSuggestionRegistrarTest {

    @Test
    @Config(sdk = [26])
    fun syncSuggestions_onApi26DoesNotCallSuggestionApis() {
        val registrar = WifiReconnectSuggestionRegistrar(ApplicationProvider.getApplicationContext())
        val message =
            registrar.syncSuggestions(
                previousSnapshot = WifiReconnectSnapshot(),
                currentSnapshot =
                    WifiReconnectSnapshot(
                        enabled = true,
                        primaryNetwork = WifiReconnectNetwork(ssid = "campus", password = "password123"),
                    ),
            )

        assertThat(message).isEqualTo("配置已保存，当前 Android 版本不支持系统 Wi-Fi 建议")
    }
}
```

- [ ] **Step 2: Extend sign-in monitoring test**

In `SignInMonitoringViewModelTest.kt`, add:

```kotlin
@Test
fun refresh_showsPlaceholderWhenLoadTakesLongerThanPlaceholderWindow() = runTest {
    val source =
        object : SignInMonitoringDataSource {
            override suspend fun load(
                rangeStartEpochSeconds: Long,
                rangeEndEpochSeconds: Long,
            ): SignInMonitoringSnapshot {
                kotlinx.coroutines.delay(500)
                return SignInMonitoringSnapshot(
                    signInAudits = emptyList(),
                    beaconScanAudits = emptyList(),
                    errorAggregates = emptyList(),
                )
            }
        }

    val viewModel = SignInMonitoringViewModel(source = source, nowEpochSeconds = { 1_800_000_000 })
    kotlinx.coroutines.test.advanceTimeBy(250)

    assertThat(viewModel.uiState.showPlaceholder).isTrue()

    kotlinx.coroutines.test.advanceUntilIdle()

    assertThat(viewModel.uiState.isLoading).isFalse()
    assertThat(viewModel.uiState.showPlaceholder).isFalse()
    assertThat(viewModel.uiState.emptyMessage).isEqualTo("暂无记录")
}
```

- [ ] **Step 3: Run tests to verify current behavior**

Run: `.\gradlew.bat :app:testDebugUnitTest --console=plain --tests com.wuyi.libraryauto.ui.screen.settings.WifiReconnectSuggestionRegistrarTest --tests com.wuyi.libraryauto.ui.screen.settings.SignInMonitoringViewModelTest`

Expected: Wi-Fi test fails if API guard is missing; sign-in test passes or fails depending on existing scheduler timing, then the implementation below makes it stable.

- [ ] **Step 4: Guard Wi-Fi suggestions by SDK**

In `WifiReconnectSuggestionRegistrar.kt`, add imports:

```kotlin
import android.os.Build
import androidx.annotation.RequiresApi
```

Replace `syncSuggestions` with:

```kotlin
fun syncSuggestions(
    previousSnapshot: WifiReconnectSnapshot,
    currentSnapshot: WifiReconnectSnapshot,
): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return if (currentSnapshot.enabled) {
            "配置已保存，当前 Android 版本不支持系统 Wi-Fi 建议"
        } else {
            "已关闭后台 Wi-Fi 重连"
        }
    }
    return syncSuggestionsApi29(previousSnapshot, currentSnapshot)
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun syncSuggestionsApi29(
    previousSnapshot: WifiReconnectSnapshot,
    currentSnapshot: WifiReconnectSnapshot,
): String {
    val previousSuggestions = previousSnapshot.toSuggestions()
    if (previousSuggestions.isNotEmpty()) {
        runCatching { wifiManager.removeNetworkSuggestions(previousSuggestions) }
    }

    if (!currentSnapshot.enabled) {
        return "已关闭后台 Wi-Fi 重连，并移除系统里的旧建议"
    }

    val currentSuggestions = currentSnapshot.toSuggestions()
    if (currentSuggestions.isEmpty()) {
        return "配置已保存，但还没有可登记给系统的 Wi-Fi"
    }

    val status =
        runCatching { wifiManager.addNetworkSuggestions(currentSuggestions) }
            .getOrElse { error ->
                return "配置已保存，但登记系统 Wi-Fi 建议失败：${error.message ?: "未知错误"}"
            }
    return if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
        "Wi-Fi 重连配置已保存，已登记系统自动连回建议"
    } else {
        "配置已保存，但系统没有接受 Wi-Fi 建议（状态码 $status）"
    }
}
```

Annotate `toSuggestions`:

```kotlin
@RequiresApi(Build.VERSION_CODES.Q)
private fun WifiReconnectSnapshot.toSuggestions(): List<WifiNetworkSuggestion> =
```

- [ ] **Step 5: Fix sign-in monitoring indentation explicitly**

Replace the `else` branch in `SignInMonitoringViewModel.refresh()` with:

```kotlin
} else {
    uiState = uiState.copy(showPlaceholder = true)
    snapshot.await()
}
```

- [ ] **Step 6: Fix manifest coarse location error**

In `AndroidManifest.xml`, add coarse location next to fine location:

```xml
    <uses-permission
        android:name="android.permission.ACCESS_COARSE_LOCATION"
        android:maxSdkVersion="30" />
    <uses-permission
        android:name="android.permission.ACCESS_FINE_LOCATION"
        android:maxSdkVersion="30" />
```

- [ ] **Step 7: Run targeted tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --console=plain --tests com.wuyi.libraryauto.ui.screen.settings.WifiReconnectSuggestionRegistrarTest --tests com.wuyi.libraryauto.ui.screen.settings.SignInMonitoringViewModelTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Run lint**

Run: `.\gradlew.bat :app:lintDebug --console=plain`

Expected: BUILD SUCCESSFUL. Existing warnings may remain, but lint must report `0 errors`.

- [ ] **Step 9: Commit**

```bash
git add library-android/app/src/main/java/com/wuyi/libraryauto/ui/screen/settings/WifiReconnectSuggestionRegistrar.kt library-android/app/src/main/java/com/wuyi/libraryauto/ui/screen/settings/SignInMonitoringViewModel.kt library-android/app/src/main/AndroidManifest.xml library-android/app/src/test/java/com/wuyi/libraryauto/ui/screen/settings/WifiReconnectSuggestionRegistrarTest.kt library-android/app/src/test/java/com/wuyi/libraryauto/ui/screen/settings/SignInMonitoringViewModelTest.kt
git commit -m "fix(android): clear lint API compatibility errors"
```

---

### Task 6: Final Android Verification

**Files:**
- Modify only if a prior verification command exposes a compile or lint issue in a touched file.

**Interfaces:**
- Consumes: All outputs from Tasks 1-5.
- Produces: Verified Android build, unit tests, lint, and optional device smoke test results.

- [ ] **Step 1: Run Kotlin compile**

Run: `.\gradlew.bat :app:compileDebugKotlin --console=plain`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run full app unit tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --console=plain`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run lint**

Run: `.\gradlew.bat :app:lintDebug --console=plain`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run smoke navigation on emulator or device**

Run: `.\gradlew.bat :app:connectedDebugAndroidTest --console=plain -Pandroid.testInstrumentationRunnerArguments.class=com.wuyi.libraryauto.SmokeNavigationTest`

Expected: BUILD SUCCESSFUL. If no emulator or device is connected, record the skipped command and do not mark this step complete.

- [ ] **Step 5: Manual verification on device**

Install debug APK and verify these exact flows:

```text
1. Launch app -> 首页 appears.
2. Tap 手动预约 -> 选择账号 screen appears -> system Back returns to 首页.
3. Tap 座位状态 -> 座位状态 screen or 暂无账号座位状态 appears -> system Back returns to 首页.
4. Tap 自动任务 -> 自动任务 screen appears.
5. Open 座位状态 with at least one saved account -> tap 一键补约 -> progress banner appears -> result dialog appears.
6. On Android 8.0 or Android 9 emulator, save Wi-Fi reconnect settings -> app does not crash and says 当前 Android 版本不支持系统 Wi-Fi 建议.
```

- [ ] **Step 6: Commit final verification note**

If all automated commands pass, create a short note:

```markdown
## 2026-07-04 Android Quality Fix Verification

- `.\gradlew.bat :app:compileDebugKotlin --console=plain`: PASS
- `.\gradlew.bat :app:testDebugUnitTest --console=plain`: PASS
- `.\gradlew.bat :app:lintDebug --console=plain`: PASS
- `.\gradlew.bat :app:connectedDebugAndroidTest --console=plain -Pandroid.testInstrumentationRunnerArguments.class=com.wuyi.libraryauto.SmokeNavigationTest`: PASS or SKIPPED with reason
```

Save it in `library-android/IMPLEMENTATION_SUMMARY.md` under the existing content, then commit:

```bash
git add library-android/IMPLEMENTATION_SUMMARY.md
git commit -m "docs(android): record quality fix verification"
```

---

## Self-Review

**Spec coverage:**
- Unreachable Home routes: Task 1 restores visible entries and tests navigation.
- Smart recommendation tie bug: Task 2 adds ranking tests and fixes comparator.
- Unsafe one-click makeup reservation: Task 3 resolves `entryUrl` and `roomId`, skips active booking dates, and tests request construction.
- Batch operation perceived slowness: Task 4 adds progress and failure banners.
- Lint blockers: Task 5 fixes API 29 Wi-Fi calls, suspicious indentation, and fine/coarse location manifest error.
- Final validation: Task 6 runs compile, full unit tests, lint, and smoke navigation.

**Placeholder scan:** The scan found no banned placeholder patterns and no unspecified edge-case instruction.

**Type consistency:** The plan consistently uses `SeatLookupRepository`, `SeatLookupQuery`, `SeatLookupLoadResult`, `BatchReservationResult`, `ReservationResult.skipped`, `SeatDisplayUiState.batchProgressMessage`, and `SeatDisplayUiState.batchErrorMessage` across the tasks.
