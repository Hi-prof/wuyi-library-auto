package com.wuyi.libraryauto.core.runtime.worker

import androidx.work.ListenableWorker.Result
import com.wuyi.libraryauto.core.domain.model.CheckInWindow
import com.wuyi.libraryauto.core.domain.usecase.ReservationWindow
import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.core.network.auth.SessionBundle
import com.wuyi.libraryauto.core.network.seat.BookingDetail
import com.wuyi.libraryauto.core.storage.credentials.SavedAccountStore
import com.wuyi.libraryauto.core.storage.db.AutomationPlanEntity
import com.wuyi.libraryauto.core.storage.db.ExecutionLogEntity
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationPlanWorkerTest {

    @Test
    fun `worker reserves all missing continuous windows and updates next run`() = runTest {
        val dependencies = FakeAutomationPlanWorkerDependencies()

        val result =
            AutomationPlanWorker.executeOnce(
                planId = "plan-1",
                nowEpochSeconds = 1_712_800_000L,
                dependencies = dependencies,
            )

        assertEquals(Result.success(), result)
        // BUG-RATE-LIMIT 修复：不再让 worker 自己 30 分钟续排，避免 N 个 plan = N 路独立轮询。
        // 巡检改由 PeriodicCheckInWorker 周期触发，schedule 不再被调用。
        assertEquals(null, dependencies.lastScheduledPlanId)
        assertEquals(null, dependencies.lastScheduledRunAtEpochSeconds)
        assertEquals(1_712_800_000L, dependencies.savedPlan?.lastRunAtEpochSeconds)
        // nextRunAtEpochSeconds 字段仍写入用于查询展示，但不再驱动调度。
        assertEquals(1_712_801_800L, dependencies.savedPlan?.nextRunAtEpochSeconds)
        assertEquals(listOf("booking-166", "booking-167"), dependencies.savedTasks.map { it.bookingId })
        assertEquals(listOf("20230001:booking-166", "20230001:booking-167"), dependencies.savedTasks.map { it.id })
        assertEquals(listOf("12,16", "12,16"), dependencies.savedTasks.map { it.expectedMinorsCsv })
        assertEquals(listOf(600L, 600L), dependencies.savedTasks.map { it.limitSignAgoSeconds })
        // 写库前 limitSignBackSeconds 会被截到 30 分钟（1800s），与周期签到口径一致。
        assertEquals(listOf(1_800L, 1_800L), dependencies.savedTasks.map { it.limitSignBackSeconds })
        assertEquals(listOf("20230001:booking-166", "20230001:booking-167"), dependencies.guardTaskIds)
        assertNotNull(dependencies.lastExecutionLog)
    }

    @Test
    fun `worker preserves success skip and failure messages for continuous windows`() = runTest {
        val dependencies =
            FakeAutomationPlanWorkerDependencies().apply {
                reservationFailuresByBeginTime[beginTimeEpochSeconds("2024-04-13", 8)] =
                    IllegalArgumentException("自习室圆形二楼 166 号座位当前不可预约，请稍后重试。")
            }

        val result =
            AutomationPlanWorker.executeOnce(
                planId = "plan-1",
                nowEpochSeconds = 1_712_800_000L,
                dependencies = dependencies,
            )

        assertEquals(Result.success(), result)
        assertEquals(
            "2024-04-11 9:00-22:00 · 已提交 自习室圆形二楼 166 号座位预约；" +
                "2024-04-12 8:00-22:00 · 该日期已存在预约，已跳过；" +
                "2024-04-13 8:00-22:00 · 自习室圆形二楼 166 号座位当前不可预约，请稍后重试。",
            dependencies.savedPlan?.lastResultMessage,
        )
    }

    @Test
    fun `worker retries recoverable reservation failure once and then succeeds`() = runTest {
        val targetBeginTime = beginTimeEpochSeconds("2024-04-11", 9)
        val dependencies =
            FakeAutomationPlanWorkerDependencies().apply {
                bookedDates += "2024-04-13"
                reservationFailuresBeforeSuccess[targetBeginTime] = mutableListOf(IOException("timeout"))
            }

        val result =
            AutomationPlanWorker.executeOnce(
                planId = "plan-1",
                nowEpochSeconds = 1_712_800_000L,
                dependencies = dependencies,
            )

        assertEquals(Result.success(), result)
        assertEquals(2, dependencies.reserveAttemptsByBeginTime[targetBeginTime])
        assertTrue(
            dependencies.savedPlan?.lastResultMessage?.contains("已提交 自习室圆形二楼 166 号座位预约") == true,
        )
    }

    @Test
    fun `worker retries rate limited reservation failure once and then succeeds`() = runTest {
        val targetBeginTime = beginTimeEpochSeconds("2024-04-12", 8)
        val dependencies =
            FakeAutomationPlanWorkerDependencies().apply {
                bookedDates.clear()
                reservationFailuresBeforeSuccess[targetBeginTime] =
                    mutableListOf(IllegalArgumentException("请求太频繁了，请稍后再试"))
            }

        val result =
            AutomationPlanWorker.executeOnce(
                planId = "plan-1",
                nowEpochSeconds = 1_712_800_000L,
                dependencies = dependencies,
            )

        assertEquals(Result.success(), result)
        assertEquals(2, dependencies.reserveAttemptsByBeginTime[targetBeginTime])
        assertEquals(1, dependencies.retryPauseCallCount)
        assertEquals(3, dependencies.savedTasks.size)
    }

    @Test
    fun `worker does not retry non recoverable reservation failure`() = runTest {
        val targetBeginTime = beginTimeEpochSeconds("2024-04-11", 9)
        val dependencies =
            FakeAutomationPlanWorkerDependencies().apply {
                bookedDates += "2024-04-13"
                reservationFailuresByBeginTime[targetBeginTime] = IllegalArgumentException("座位不可预约")
            }

        val result =
            AutomationPlanWorker.executeOnce(
                planId = "plan-1",
                nowEpochSeconds = 1_712_800_000L,
                dependencies = dependencies,
            )

        assertEquals(Result.success(), result)
        assertEquals(1, dependencies.reserveAttemptsByBeginTime[targetBeginTime])
    }

    @Test
    fun `worker logs empty ibeacon and fallback window when booking detail has no minors`() = runTest {
        val dependencies =
            FakeAutomationPlanWorkerDependencies().apply {
                bookedDates += "2024-04-12"
                bookedDates += "2024-04-13"
                bookingDetailsByBookingId["booking-166"] =
                    BookingDetail(
                        bookingId = "booking-166",
                        window =
                            CheckInWindow(
                                startEpochSeconds = 1_712_800_000L,
                                limitSignAgoSeconds = CheckInWindow.FALLBACK_SECONDS,
                                limitSignBackSeconds = CheckInWindow.FALLBACK_SECONDS,
                            ),
                        expectedMinors = emptyList(),
                        statusLabel = "待签到",
                        isAlreadySignedIn = false,
                    )
            }

        val result =
            AutomationPlanWorker.executeOnce(
                planId = "plan-1",
                nowEpochSeconds = 1_712_800_000L,
                dependencies = dependencies,
            )

        assertEquals(Result.success(), result)
        assertEquals("", dependencies.savedTasks.single().expectedMinorsCsv)
        assertEquals(CheckInWindow.FALLBACK_SECONDS, dependencies.savedTasks.single().limitSignBackSeconds)
        assertTrue(dependencies.lastExecutionLog?.message?.contains("接口未返回有效蓝牙设备信息") == true)
        assertTrue(dependencies.lastExecutionLog?.message?.contains("使用兜底签到窗口") == true)
    }

    private class FakeAutomationPlanWorkerDependencies : AutomationPlanWorkerDependencies {
        private val session =
            AuthenticatedSession(
                session = SessionBundle(cookieHeader = "auth=token", userId = "405963"),
                cookies = emptyList(),
                currentUserJson = """{"id":"405963"}""",
                origin = "https://example.com",
                installationId = "install-1",
            )

        var savedPlan: AutomationPlanEntity? = null
        val savedTasks = mutableListOf<ReservationTaskEntity>()
        var lastScheduledPlanId: String? = null
        var lastScheduledRunAtEpochSeconds: Long? = null
        val guardTaskIds = mutableListOf<String>()
        var lastExecutionLog: ExecutionLogEntity? = null
        val bookedDates = mutableSetOf("2024-04-12")
        val reserveAttemptsByBeginTime = mutableMapOf<Int, Int>()
        val reservationFailuresByBeginTime = mutableMapOf<Int, Throwable>()
        val reservationFailuresBeforeSuccess = mutableMapOf<Int, MutableList<Throwable>>()
        val bookingDetailsByBookingId = mutableMapOf<String, BookingDetail>()
        var pauseBetweenAttemptsCallCount = 0
        var retryPauseCallCount = 0
        var loginCalled = false
        private var nextBookingNumber = 166

        override suspend fun findPlan(planId: String): AutomationPlanEntity? =
            AutomationPlanEntity(
                planId = planId,
                studentId = "20230001",
                roomName = "自习室圆形二楼",
                seatNumber = "166",
                mode = "CONTINUOUS",
                singleDate = null,
                singleStartTime = null,
                singleEndTime = null,
                enabled = true,
                createdAtEpochSeconds = 1_712_700_000L,
                updatedAtEpochSeconds = 1_712_700_000L,
                nextRunAtEpochSeconds = null,
                lastRunAtEpochSeconds = null,
                lastResultMessage = "",
            )

        override fun loadSavedAccount(studentId: String): SavedAccountStore.SavedAccount? =
            SavedAccountStore.SavedAccount(
                studentId = studentId,
                "secret",
                preferredRoomName = "自习室圆形二楼",
                preferredSeatNumber = "166",
            )

        override fun login(
            studentId: String,
            password: String,
        ): AuthenticatedSession {
            loginCalled = true
            return session
        }

        override fun buildContinuousWindows(nowEpochSeconds: Long): List<ReservationWindow> =
            listOf(
                ReservationWindow(
                    targetDate = "2024-04-11",
                    startHour = 9,
                    endHour = 22,
                ),
                ReservationWindow(
                    targetDate = "2024-04-12",
                    startHour = 8,
                    endHour = 22,
                ),
                ReservationWindow(
                    targetDate = "2024-04-13",
                    startHour = 8,
                    endHour = 22,
                ),
            )

        override fun loadBookedDates(
            plan: AutomationPlanEntity,
            session: AuthenticatedSession,
        ): Set<String> = bookedDates.toSet()

        override fun reserveSeat(
            plan: AutomationPlanEntity,
            session: AuthenticatedSession,
            beginTimeEpochSeconds: Int,
            durationSeconds: Int,
        ): AutomationPlanReservationResult {
            reserveAttemptsByBeginTime[beginTimeEpochSeconds] =
                (reserveAttemptsByBeginTime[beginTimeEpochSeconds] ?: 0) + 1
            reservationFailuresBeforeSuccess[beginTimeEpochSeconds]?.let { stagedFailures ->
                if (stagedFailures.isNotEmpty()) {
                    throw stagedFailures.removeAt(0)
                }
            }
            reservationFailuresByBeginTime[beginTimeEpochSeconds]?.let { throw it }
            return AutomationPlanReservationResult(
                bookingId = "booking-${nextBookingNumber++}",
                roomName = plan.roomName,
                seatNumber = plan.seatNumber,
                message = "已提交 ${plan.roomName} ${plan.seatNumber} 号座位预约",
            )
        }

        override fun loadBookingDetail(
            session: AuthenticatedSession,
            bookingId: String,
        ): BookingDetail =
            bookingDetailsByBookingId[bookingId]
                ?: BookingDetail(
                    bookingId = bookingId,
                    window =
                        CheckInWindow(
                            startEpochSeconds = 1_712_800_000L,
                            limitSignAgoSeconds = 600L,
                            limitSignBackSeconds = 2_400L,
                        ),
                    expectedMinors = listOf(16, 12, 12),
                    statusLabel = "待签到",
                    isAlreadySignedIn = false,
                )

        override suspend fun pauseBetweenReservationAttempts() {
            pauseBetweenAttemptsCallCount += 1
        }

        override suspend fun pauseBeforeReservationRetry(error: Throwable) {
            retryPauseCallCount += 1
        }

        override suspend fun upsertReservationTask(task: ReservationTaskEntity) {
            savedTasks += task
        }

        override suspend fun insertExecutionLog(log: ExecutionLogEntity) {
            lastExecutionLog = log
        }

        override suspend fun updatePlan(plan: AutomationPlanEntity) {
            savedPlan = plan
        }

        override fun schedule(
            planId: String,
            nextRunAtEpochSeconds: Long,
        ) {
            lastScheduledPlanId = planId
            lastScheduledRunAtEpochSeconds = nextRunAtEpochSeconds
        }

        override fun enqueueGuard(
            taskId: String,
            startTimeEpochSeconds: Long,
            limitSignAgoSeconds: Long,
        ) {
            guardTaskIds += taskId
        }
    }

    private companion object {
        private val shanghaiZoneId: ZoneId = ZoneId.of("Asia/Shanghai")
    }

    private fun beginTimeEpochSeconds(
        targetDate: String,
        startHour: Int,
    ): Int =
        LocalDate.parse(targetDate)
            .atTime(LocalTime.of(startHour, 0))
            .atZone(shanghaiZoneId)
            .toEpochSecond()
            .toInt()
}
