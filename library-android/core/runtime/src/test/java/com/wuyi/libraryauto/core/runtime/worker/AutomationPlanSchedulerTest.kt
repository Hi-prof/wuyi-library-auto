package com.wuyi.libraryauto.core.runtime.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.wuyi.libraryauto.core.domain.model.CheckInWindow
import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.core.network.auth.SessionBundle
import com.wuyi.libraryauto.core.network.seat.BookingDetail
import com.wuyi.libraryauto.core.runtime.network.NetworkRecoveryResult
import com.wuyi.libraryauto.core.storage.credentials.SavedAccountStore
import com.wuyi.libraryauto.core.storage.db.AutomationPlanEntity
import com.wuyi.libraryauto.core.storage.db.ExecutionLogEntity
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutomationPlanSchedulerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val workerExecutor = Executors.newSingleThreadExecutor()

    @After
    fun tearDown() {
        runCatching {
            WorkManager.getInstance(context).cancelAllWork()
        }
        workerExecutor.shutdownNow()
    }

    @Test
    fun `schedule does not cancel running work when same plan is scheduled again`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(workerExecutor)
                .setTaskExecutor(SynchronousExecutor())
                .build(),
        )
        val dependencies = BlockingSingleReservationDependencies(context)
        AutomationPlanWorkerProvider.install { dependencies }
        val scheduler = AutomationPlanScheduler(context)
        val nowEpochSeconds = 1_712_800_000L

        scheduler.schedule(
            planId = "plan-1",
            nextRunAtEpochSeconds = nowEpochSeconds,
            nowEpochSeconds = nowEpochSeconds,
        )

        assertTrue(dependencies.started.await(5, TimeUnit.SECONDS))
        assertTrue(currentStates().contains(WorkInfo.State.RUNNING))

        scheduler.schedule(
            planId = "plan-1",
            nextRunAtEpochSeconds = nowEpochSeconds + 1_800L,
            nowEpochSeconds = nowEpochSeconds,
        )

        val statesAfterReschedule = waitForStates()
        assertTrue(statesAfterReschedule.contains(WorkInfo.State.RUNNING))
        assertFalse(statesAfterReschedule.contains(WorkInfo.State.CANCELLED))

        dependencies.allowCompletion.countDown()
        waitForTerminalState()
    }

    private fun currentStates(): List<WorkInfo.State> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("automation-plan:plan-1")
            .get(5, TimeUnit.SECONDS)
            .map { it.state }

    private fun waitForStates(): List<WorkInfo.State> {
        repeat(40) {
            val states = currentStates()
            if (states.isNotEmpty()) {
                return states
            }
            Thread.sleep(50)
        }
        return currentStates()
    }

    private fun waitForTerminalState() {
        repeat(40) {
            val states = currentStates()
            if (states.all(WorkInfo.State::isFinished)) {
                return
            }
            Thread.sleep(50)
        }
    }

    private class BlockingSingleReservationDependencies(
        appContext: Context,
    ) : AutomationPlanWorkerDependencies {
        private val scheduler = AutomationPlanScheduler(appContext.applicationContext)
        private val session =
            AuthenticatedSession(
                session = SessionBundle(cookieHeader = "auth=token", userId = "20230001"),
                cookies = emptyList(),
                currentUserJson = """{"id":"20230001"}""",
                origin = "https://example.com",
                installationId = "install-1",
            )

        val started = CountDownLatch(1)
        val allowCompletion = CountDownLatch(1)

        override suspend fun ensureNetworkForBackgroundWork(): NetworkRecoveryResult =
            NetworkRecoveryResult(recovered = true, message = "网络可用")

        override suspend fun findPlan(planId: String): AutomationPlanEntity =
            AutomationPlanEntity(
                planId = planId,
                studentId = "20230001",
                roomName = "自习室圆形二楼",
                seatNumber = "166",
                mode = "SINGLE_CUSTOM",
                singleDate = "2026-04-11",
                singleStartTime = "09:00",
                singleEndTime = "12:00",
                enabled = true,
                createdAtEpochSeconds = 100L,
                updatedAtEpochSeconds = 100L,
                nextRunAtEpochSeconds = 100L,
                lastRunAtEpochSeconds = null,
                lastResultMessage = "",
            )

        override fun loadSavedAccount(studentId: String): SavedAccountStore.SavedAccount =
            SavedAccountStore.SavedAccount(
                studentId = studentId,
                password = "secret",
                preferredRoomName = "自习室圆形二楼",
                preferredSeatNumber = "166",
            )

        override fun login(
            studentId: String,
            password: String,
        ): AuthenticatedSession = session

        override fun buildContinuousWindows(nowEpochSeconds: Long) = error("unused")

        override fun loadBookedDates(
            plan: AutomationPlanEntity,
            session: AuthenticatedSession,
        ): Set<String> = emptySet()

        override fun reserveSeat(
            plan: AutomationPlanEntity,
            session: AuthenticatedSession,
            beginTimeEpochSeconds: Int,
            durationSeconds: Int,
        ): AutomationPlanReservationResult {
            started.countDown()
            check(allowCompletion.await(5, TimeUnit.SECONDS)) { "worker did not finish in time" }
            return AutomationPlanReservationResult(
                bookingId = "booking-1",
                roomName = plan.roomName,
                seatNumber = plan.seatNumber,
                message = "已提交 ${plan.roomName} ${plan.seatNumber} 号座位预约",
            )
        }

        override fun loadBookingDetail(
            session: AuthenticatedSession,
            bookingId: String,
        ): BookingDetail =
            BookingDetail(
                bookingId = bookingId,
                window =
                    CheckInWindow(
                        startEpochSeconds = 1_776_000_000L,
                        limitSignAgoSeconds = 900L,
                        limitSignBackSeconds = 1_800L,
                    ),
                expectedMinors = listOf(12),
                statusLabel = "待签到",
                isAlreadySignedIn = false,
            )

        override suspend fun pauseBetweenReservationAttempts() = Unit

        override suspend fun pauseBeforeReservationRetry(error: Throwable) = Unit

        override suspend fun upsertReservationTask(task: ReservationTaskEntity) = Unit

        override suspend fun insertExecutionLog(log: ExecutionLogEntity) = Unit

        override suspend fun updatePlan(plan: AutomationPlanEntity) = Unit

        override fun schedule(
            planId: String,
            nextRunAtEpochSeconds: Long,
        ) {
            scheduler.schedule(planId, nextRunAtEpochSeconds)
        }

        override fun enqueueGuard(
            taskId: String,
            startTimeEpochSeconds: Long,
            limitSignAgoSeconds: Long,
        ) = Unit
    }
}
