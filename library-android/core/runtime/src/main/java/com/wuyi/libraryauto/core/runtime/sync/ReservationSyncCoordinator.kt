package com.wuyi.libraryauto.core.runtime.sync

import com.wuyi.libraryauto.core.domain.model.CheckInWindow
import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import com.wuyi.libraryauto.core.domain.usecase.BookingCheckInMutexRegistry
import com.wuyi.libraryauto.core.network.seat.BookingDetail
import com.wuyi.libraryauto.core.storage.db.ReservationTaskDao
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import com.wuyi.libraryauto.core.storage.db.buildReservationTaskId

/**
 * 把账号「远端活跃预约」同步成本地 `reservation_tasks` 行，并为每条预约调度独立的
 * GuardWorker。覆盖三个触发场景：
 *
 * 1. 用户/管理员在网页或别的端预约了座位（App 没经手）；
 * 2. App 启动 / 网络恢复 / 校园网认证恢复 / 登录成功后，需要把缺失的 Guard 任务补齐；
 * 3. 周期签到运行时把 mutex registry 的过期 bookingId 一并清理，避免 20+ 账号下
 *    `BookingCheckInMutexRegistry` 长期累积。
 *
 * 该类不直接发起网络请求，而是接收已经从 [com.wuyi.libraryauto.core.network.seat.SeatBookingStatusService.loadActiveBookingDetails]
 * 拿到的 [BookingDetail] 列表，方便复用到 Worker 与 UI 层。
 */
class ReservationSyncCoordinator(
    private val reservationTaskDao: ReservationTaskDao,
    private val guardScheduler: GuardScheduler,
    private val mutexRegistry: BookingCheckInMutexRegistry = BookingCheckInMutexRegistry.shared,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    /**
     * 同步指定账号的预约。
     *
     * @param studentId 学生学号。
     * @param remoteBookings 学校接口返回的当前活跃预约（已签到 / 待签到，过滤掉已结束/取消）。
     * @return 实际入队 GuardWorker 的预约数（包含新增和窗口仍开放需要重新排队的）。
     */
    suspend fun syncAccount(
        studentId: String,
        remoteBookings: List<BookingDetail>,
    ): SyncOutcome {
        val safeStudentId = studentId.trim()
        require(safeStudentId.isNotBlank()) { "studentId must not be blank" }
        val now = nowEpochSeconds()
        val localTasks = reservationTaskDao.listForStudent(safeStudentId)
        val remoteByBookingId =
            remoteBookings
                .asSequence()
                .filter { detail -> detail.bookingId.isNotBlank() }
                .associateBy { detail -> detail.bookingId.trim() }

        var enqueued = 0
        var upserted = 0
        remoteByBookingId.forEach { (bookingId, detail) ->
            val taskId = buildReservationTaskId(safeStudentId, bookingId)
            val existing = localTasks.firstOrNull { task -> task.id == taskId }
            val cappedSignBack = CheckInWindow.capSignBackSeconds(detail.window.limitSignBackSeconds)
            val targetState =
                when {
                    detail.isAlreadySignedIn -> ReservationTaskState.SIGNIN_SUCCESS
                    existing != null && existing.state == ReservationTaskState.SIGNIN_SUCCESS ->
                        ReservationTaskState.SIGNIN_SUCCESS
                    existing != null &&
                        existing.state == ReservationTaskState.FAILED_MANUAL_ACTION ->
                        // 用户手工放弃后，不要因为一次远端 sync 又把状态拉回去。
                        ReservationTaskState.FAILED_MANUAL_ACTION
                    else -> ReservationTaskState.RESERVED_WAITING_SIGNIN
                }
            val expectedMinorsCsv = detail.expectedMinors.toMinorCsv()
            val merged =
                ReservationTaskEntity(
                    id = taskId,
                    studentId = safeStudentId,
                    roomName = existing?.roomName.orEmpty(),
                    seatNumber = existing?.seatNumber.orEmpty(),
                    state = targetState,
                    bookingId = bookingId,
                    startTimeEpochSeconds = detail.window.startEpochSeconds,
                    limitSignAgoSeconds = detail.window.limitSignAgoSeconds,
                    limitSignBackSeconds = cappedSignBack,
                    expectedMinorsCsv = expectedMinorsCsv.ifBlank { existing?.expectedMinorsCsv.orEmpty() },
                    lastError = existing?.lastError,
                    lastGuardAttemptEpochSeconds = existing?.lastGuardAttemptEpochSeconds,
                    consecutiveRetryCount = existing?.consecutiveRetryCount ?: 0,
                )
            val needUpsert =
                existing == null ||
                    existing.state != merged.state ||
                    existing.startTimeEpochSeconds != merged.startTimeEpochSeconds ||
                    existing.limitSignAgoSeconds != merged.limitSignAgoSeconds ||
                    existing.limitSignBackSeconds != merged.limitSignBackSeconds ||
                    existing.expectedMinorsCsv != merged.expectedMinorsCsv ||
                    existing.bookingId.orEmpty() != merged.bookingId.orEmpty()
            if (needUpsert) {
                reservationTaskDao.upsert(merged)
                upserted += 1
            }
            // 仅对仍在签到窗口（含未到签到时间窗）的预约重新入队 GuardWorker，避免对已成功的任务重复排队。
            if (merged.state != ReservationTaskState.SIGNIN_SUCCESS &&
                merged.state != ReservationTaskState.FAILED_MANUAL_ACTION &&
                merged.checkInDeadlineEpochSeconds() > now
            ) {
                guardScheduler.schedule(
                    taskId = merged.id,
                    startTimeEpochSeconds = merged.startTimeEpochSeconds,
                    limitSignAgoSeconds = merged.limitSignAgoSeconds,
                )
                enqueued += 1
            }
        }
        // 顺手按账号清理 mutex：保留远端仍活跃的 bookingId 与本地仍处于 guardable 状态的 bookingId。
        val keepBookingIds =
            buildSet {
                remoteByBookingId.keys.forEach(::add)
                localTasks
                    .filter { task ->
                        task.state == ReservationTaskState.RESERVED_WAITING_SIGNIN ||
                            task.state == ReservationTaskState.GUARD_SCHEDULED
                    }
                    .mapNotNull { task -> task.bookingId?.trim()?.takeIf(String::isNotBlank) }
                    .forEach(::add)
            }
        mutexRegistry.evictIdle(keepBookingIds)
        return SyncOutcome(
            studentId = safeStudentId,
            upsertedCount = upserted,
            enqueuedGuardCount = enqueued,
            keptBookingIds = keepBookingIds,
        )
    }

    private fun ReservationTaskEntity.checkInDeadlineEpochSeconds(): Long =
        startTimeEpochSeconds + limitSignBackSeconds

    private fun List<Int>.toMinorCsv(): String =
        asSequence()
            .filter { minor -> minor in 0..65_535 }
            .distinct()
            .sorted()
            .take(MAX_EXPECTED_MINORS)
            .joinToString(",")

    fun interface GuardScheduler {
        fun schedule(
            taskId: String,
            startTimeEpochSeconds: Long,
            limitSignAgoSeconds: Long,
        )
    }

    data class SyncOutcome(
        val studentId: String,
        val upsertedCount: Int,
        val enqueuedGuardCount: Int,
        val keptBookingIds: Set<String>,
    )

    private companion object {
        private const val MAX_EXPECTED_MINORS = 256
    }
}
