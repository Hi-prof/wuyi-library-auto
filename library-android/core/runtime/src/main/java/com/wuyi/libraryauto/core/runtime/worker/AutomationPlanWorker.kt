package com.wuyi.libraryauto.core.runtime.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wuyi.libraryauto.core.domain.model.CheckInWindow
import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import com.wuyi.libraryauto.core.network.auth.toLoginErrorMessage
import com.wuyi.libraryauto.core.network.seat.BookingDetail
import com.wuyi.libraryauto.core.storage.db.AutomationPlanEntity
import com.wuyi.libraryauto.core.storage.db.ExecutionLogEntity
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import com.wuyi.libraryauto.core.storage.db.buildReservationTaskId
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class AutomationPlanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val planId = inputData.getString(KEY_PLAN_ID) ?: return Result.failure()
        return executeOnce(planId, System.currentTimeMillis() / 1000, AutomationPlanWorkerProvider.get(applicationContext))
    }

    companion object {
        internal const val KEY_PLAN_ID = "planId"
        private const val RETRY_INTERVAL_SECONDS = 30 * 60L
        private val shanghaiZoneId: ZoneId = ZoneId.of("Asia/Shanghai")

        internal suspend fun executeOnce(
            planId: String,
            nowEpochSeconds: Long,
            dependencies: AutomationPlanWorkerDependencies,
        ): Result {
            val plan = dependencies.findPlan(planId) ?: return Result.failure()
            if (!plan.enabled) {
                return Result.success()
            }

            val windows =
                runCatching { buildWindows(plan, nowEpochSeconds, dependencies) }
                    .getOrElse { error ->
                        persistPlan(plan, nowEpochSeconds, error.message ?: "自动任务配置无效", null, dependencies)
                        return Result.success()
                    }
            val account = dependencies.loadSavedAccount(plan.studentId)
            val nextRunAtOnFailure = buildNextRunAt(plan, windows, nowEpochSeconds, success = false)
            if (account == null) {
                persistPlan(
                    plan = plan,
                    nowEpochSeconds = nowEpochSeconds,
                    message = "未找到 ${plan.studentId} 的本地账号，请先重新登录。",
                    nextRunAtEpochSeconds = nextRunAtOnFailure,
                    dependencies = dependencies,
                )
                return Result.success()
            }

            val networkRecovery = dependencies.ensureNetworkForBackgroundWork()
            if (!networkRecovery.recovered) {
                persistPlan(
                    plan = plan,
                    nowEpochSeconds = nowEpochSeconds,
                    message = networkRecovery.message,
                    nextRunAtEpochSeconds = nextRunAtOnFailure,
                    dependencies = dependencies,
                )
                return Result.success()
            }

            val session =
                runCatching {
                    dependencies.login(account.studentId, account.let { it.password })
                }.getOrElse { error ->
                    persistPlan(
                        plan = plan,
                        nowEpochSeconds = nowEpochSeconds,
                        message = error.toLoginErrorMessage(),
                        nextRunAtEpochSeconds = nextRunAtOnFailure,
                        dependencies = dependencies,
                    )
                    return Result.success()
            }

            val bookedDatesRecovery = dependencies.ensureNetworkForBackgroundWork()
            if (!bookedDatesRecovery.recovered) {
                persistPlan(
                    plan = plan,
                    nowEpochSeconds = nowEpochSeconds,
                    message = bookedDatesRecovery.message,
                    nextRunAtEpochSeconds = nextRunAtOnFailure,
                    dependencies = dependencies,
                )
                return Result.success()
            }

            val bookedDates = dependencies.loadBookedDates(plan, session).toMutableSet()
            val results = mutableListOf<WindowAttemptResult>()
            var hasAttemptedReservationRequest = false
            windows.forEach { window ->
                val targetDate = window.label.substringBefore(' ').trim()
                if (targetDate in bookedDates) {
                    results +=
                        WindowAttemptResult(
                            label = window.label,
                            wasSuccessful = false,
                            message = "该日期已存在预约，已跳过",
                        )
                    return@forEach
                }

                val reservationRecovery = dependencies.ensureNetworkForBackgroundWork()
                if (!reservationRecovery.recovered) {
                    results +=
                        WindowAttemptResult(
                            label = window.label,
                            wasSuccessful = false,
                            message = reservationRecovery.message,
                        )
                    return@forEach
                }

                val attempt =
                    attemptReservationWithRetry(
                        plan = plan,
                        session = session,
                        beginTimeEpochSeconds = window.beginTimeEpochSeconds,
                        durationSeconds = window.durationSeconds,
                        dependencies = dependencies,
                        pauseBeforeAttempt = hasAttemptedReservationRequest,
                    )
                hasAttemptedReservationRequest = true
                val reservation = attempt.getOrNull()
                if (reservation != null) {
                    val taskId = buildReservationTaskId(plan.studentId, reservation.bookingId)
                    val taskSync = loadTaskSync(
                        session = session,
                        bookingId = reservation.bookingId,
                        dependencies = dependencies,
                    )
                    val task =
                        ReservationTaskEntity(
                            id = taskId,
                            studentId = plan.studentId,
                            roomName = reservation.roomName,
                            seatNumber = reservation.seatNumber,
                            state = ReservationTaskState.RESERVED_WAITING_SIGNIN,
                            bookingId = reservation.bookingId,
                            startTimeEpochSeconds = window.beginTimeEpochSeconds.toLong(),
                            limitSignAgoSeconds = taskSync.limitSignAgoSeconds,
                            limitSignBackSeconds = taskSync.limitSignBackSeconds,
                            expectedMinorsCsv = taskSync.expectedMinorsCsv,
                            lastError = null,
                        )
                    dependencies.upsertReservationTask(task)
                    dependencies.insertExecutionLog(
                        ExecutionLogEntity(
                            taskId = task.id,
                            state = task.state,
                            recordedAtEpochSeconds = nowEpochSeconds,
                            message = taskSync.appendNotes("${reservation.message} · ${window.label}"),
                        ),
                    )
                    dependencies.enqueueGuard(
                        taskId = task.id,
                        startTimeEpochSeconds = task.startTimeEpochSeconds,
                        limitSignAgoSeconds = task.limitSignAgoSeconds,
                    )
                    bookedDates += targetDate
                    results +=
                        WindowAttemptResult(
                            label = window.label,
                            wasSuccessful = true,
                            message = reservation.message,
                        )
                    return@forEach
                }

                results +=
                    WindowAttemptResult(
                        label = window.label,
                        wasSuccessful = false,
                        message = attempt.exceptionOrNull()?.message?.takeIf(String::isNotBlank) ?: "自动预约失败",
                    )
            }

            val summary =
                results.joinToString("；") { result ->
                    "${result.label} · ${result.message}"
                }.ifBlank {
                    "未找到可预约座位，请稍后重试。"
                }

            persistPlan(
                plan = plan,
                nowEpochSeconds = nowEpochSeconds,
                message = summary,
                nextRunAtEpochSeconds =
                    buildNextRunAt(
                        plan = plan,
                        windows = windows,
                        nowEpochSeconds = nowEpochSeconds,
                        success = results.any(WindowAttemptResult::wasSuccessful),
                    ),
                dependencies = dependencies,
            )
            return Result.success()
        }

        private fun loadTaskSync(
            session: com.wuyi.libraryauto.core.network.auth.AuthenticatedSession,
            bookingId: String,
            dependencies: AutomationPlanWorkerDependencies,
        ): ReservationTaskSync =
            runCatching {
                dependencies.loadBookingDetail(session, bookingId)
            }.getOrNull()
                ?.toTaskSync()
                ?: ReservationTaskSync(
                    expectedMinorsCsv = "",
                    limitSignAgoSeconds = CheckInWindow.FALLBACK_SECONDS,
                    limitSignBackSeconds = CheckInWindow.FALLBACK_SECONDS,
                    notes = listOf("读取预约详情失败", "接口未返回有效蓝牙设备信息", "使用兜底签到窗口"),
                )

        private fun BookingDetail.toTaskSync(): ReservationTaskSync {
            val expectedMinorsCsv = expectedMinors.toMinorCsv()
            val notes = buildList {
                if (expectedMinorsCsv.isBlank()) {
                    add("接口未返回有效蓝牙设备信息")
                }
                if (window.limitSignAgoSeconds == CheckInWindow.FALLBACK_SECONDS ||
                    window.limitSignBackSeconds == CheckInWindow.FALLBACK_SECONDS
                ) {
                    add("使用兜底签到窗口")
                }
            }
            return ReservationTaskSync(
                expectedMinorsCsv = expectedMinorsCsv,
                limitSignAgoSeconds = window.limitSignAgoSeconds,
                // 与周期签到一致，把 limitSignBackSeconds 截断到 30 分钟。
                limitSignBackSeconds = CheckInWindow.capSignBackSeconds(window.limitSignBackSeconds),
                notes = notes,
            )
        }

        private fun List<Int>.toMinorCsv(): String =
            asSequence()
                .filter { minor -> minor in 0..65_535 }
                .distinct()
                .sorted()
                .take(MAX_EXPECTED_MINORS)
                .joinToString(",")

        private suspend fun attemptReservationWithRetry(
            plan: AutomationPlanEntity,
            session: com.wuyi.libraryauto.core.network.auth.AuthenticatedSession,
            beginTimeEpochSeconds: Int,
            durationSeconds: Int,
            dependencies: AutomationPlanWorkerDependencies,
            pauseBeforeAttempt: Boolean,
        ): kotlin.Result<AutomationPlanReservationResult> {
            if (pauseBeforeAttempt) {
                dependencies.pauseBetweenReservationAttempts()
            }
            val firstAttempt =
                runCatching {
                    dependencies.reserveSeat(
                        plan = plan,
                        session = session,
                        beginTimeEpochSeconds = beginTimeEpochSeconds,
                        durationSeconds = durationSeconds,
                    )
                }
            val firstError = firstAttempt.exceptionOrNull() ?: return firstAttempt
            if (!AutomationPlanReservationRetryPolicy.isRecoverableFailure(firstError)) {
                return firstAttempt
            }
            dependencies.pauseBeforeReservationRetry(firstError)
            return runCatching {
                dependencies.reserveSeat(
                    plan = plan,
                    session = session,
                    beginTimeEpochSeconds = beginTimeEpochSeconds,
                    durationSeconds = durationSeconds,
                )
            }
        }

        private suspend fun persistPlan(
            plan: AutomationPlanEntity,
            nowEpochSeconds: Long,
            message: String,
            nextRunAtEpochSeconds: Long?,
            dependencies: AutomationPlanWorkerDependencies,
        ) {
            // 单次模式 (SINGLE_CUSTOM)：buildNextRunAt 在成功 / 窗口结束时返回 null。
            // 此时把 plan 设为 disabled，让 PeriodicCheckInWorker 巡逻不再选中它，
            // 也避免后续 ReservationGuardWorker 入队后无意义的状态轮询。
            val resolvedEnabled =
                if (plan.mode != "CONTINUOUS" && nextRunAtEpochSeconds == null) {
                    false
                } else {
                    plan.enabled
                }
            val updatedPlan =
                plan.copy(
                    enabled = resolvedEnabled,
                    updatedAtEpochSeconds = nowEpochSeconds,
                    nextRunAtEpochSeconds = nextRunAtEpochSeconds,
                    lastRunAtEpochSeconds = nowEpochSeconds,
                    lastResultMessage = message,
                )
            dependencies.updatePlan(updatedPlan)
            // BUG-RATE-LIMIT 修复：不再让 worker 自己 30 分钟续排，避免 N 个 plan = N 路独立轮询打学校接口。
            // 后续巡检统一交给 PeriodicCheckInWorker（30 分钟一次）+ 5 分钟防抖窗口，触发风控的概率显著降低。
        }

        private fun buildWindows(
            plan: AutomationPlanEntity,
            nowEpochSeconds: Long,
            dependencies: AutomationPlanWorkerDependencies,
        ): List<ExecutionWindow> =
            if (plan.mode == "CONTINUOUS") {
                dependencies.buildContinuousWindows(nowEpochSeconds).map { window ->
                    val beginTimeEpochSeconds =
                        LocalDate.parse(window.targetDate)
                            .atTime(LocalTime.of(window.startHour, 0))
                            .atZone(shanghaiZoneId)
                            .toEpochSecond()
                            .toInt()
                    ExecutionWindow(
                        beginTimeEpochSeconds = beginTimeEpochSeconds,
                        durationSeconds = (window.endHour - window.startHour) * 3600,
                        endEpochSeconds = beginTimeEpochSeconds + (window.endHour - window.startHour) * 3600L,
                        label = "${window.targetDate} ${window.startHour}:00-${window.endHour}:00",
                    )
                }
            } else {
                val date = LocalDate.parse(plan.singleDate.orEmpty())
                val startTime = LocalTime.parse(plan.singleStartTime.orEmpty())
                val endTime = LocalTime.parse(plan.singleEndTime.orEmpty())
                val beginTimeEpochSeconds = date.atTime(startTime).atZone(shanghaiZoneId).toEpochSecond().toInt()
                val endEpochSeconds = date.atTime(endTime).atZone(shanghaiZoneId).toEpochSecond()
                listOf(
                    ExecutionWindow(
                        beginTimeEpochSeconds = beginTimeEpochSeconds,
                        durationSeconds = (endEpochSeconds - beginTimeEpochSeconds).toInt(),
                        endEpochSeconds = endEpochSeconds,
                        label = "${plan.singleDate} ${plan.singleStartTime}-${plan.singleEndTime}",
                    ),
                )
            }

        private fun buildNextRunAt(
            plan: AutomationPlanEntity,
            windows: List<ExecutionWindow>,
            nowEpochSeconds: Long,
            success: Boolean,
        ): Long? {
            return if (plan.mode == "CONTINUOUS") {
                nowEpochSeconds + RETRY_INTERVAL_SECONDS
            } else {
                val singleWindow = windows.firstOrNull() ?: return null
                if (success || nowEpochSeconds >= singleWindow.endEpochSeconds) {
                    null
                } else {
                    nowEpochSeconds + RETRY_INTERVAL_SECONDS
                }
            }
        }

        private data class ExecutionWindow(
            val beginTimeEpochSeconds: Int,
            val durationSeconds: Int,
            val endEpochSeconds: Long,
            val label: String,
        )

        private data class WindowAttemptResult(
            val label: String,
            val wasSuccessful: Boolean,
            val message: String,
        )

        private data class ReservationTaskSync(
            val expectedMinorsCsv: String,
            val limitSignAgoSeconds: Long,
            val limitSignBackSeconds: Long,
            val notes: List<String>,
        ) {
            fun appendNotes(message: String): String =
                if (notes.isEmpty()) {
                    message
                } else {
                    "$message · ${notes.joinToString("，")}"
                }
        }

        private const val MAX_EXPECTED_MINORS = 256
    }
}
