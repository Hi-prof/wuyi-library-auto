package com.wuyi.libraryauto.ui.repository.home

import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import com.wuyi.libraryauto.core.network.seat.SeatBookingLiveState
import com.wuyi.libraryauto.core.storage.db.ReservationTaskDao
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import com.wuyi.libraryauto.core.storage.db.SeatDisplaySnapshotDao
import com.wuyi.libraryauto.core.storage.db.SeatDisplaySnapshotEntity
import com.wuyi.libraryauto.ui.repository.task.StoredAccountSource
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class TodayOverviewRepository(
    private val accountSource: StoredAccountSource,
    private val reservationTaskDao: ReservationTaskDao,
    private val seatDisplaySnapshotDao: SeatDisplaySnapshotDao,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend fun load(): TodayOverviewSnapshot {
        val zoneId = clock.zone
        val today = LocalDate.now(clock)
        val accounts =
            accountSource
                .readStoredAccounts()
                .map { it.studentId.trim() }
                .filter(String::isNotBlank)
                .distinct()

        val taskGroups =
            reservationTaskDao
                .listAll()
                .filter { task -> task.startTimeEpochSeconds.isSameLocalDate(today, zoneId) }
                .groupBy { task -> task.studentId.trim() }
        val todaySnapshots =
            seatDisplaySnapshotDao
                .listAll()
                .filter { snapshot ->
                    (snapshot.updatedAtEpochMillis / 1_000L).isSameLocalDate(today, zoneId)
                }
                .associateBy { snapshot -> snapshot.studentId.trim() }

        val accountSummaries =
            accounts.mapNotNull { studentId ->
                val counters =
                    taskGroups[studentId]
                        .orEmpty()
                        .toCounters()
                        .mergeSnapshot(todaySnapshots[studentId])
                if (!counters.hasTodayData()) {
                    null
                } else {
                    counters.toAccountSummary(studentId)
                }
            }
                .sortedWith(
                    compareByDescending<TodayAccountOverview> { it.totalTaskCount }
                        .thenByDescending { it.reservedSeatCount }
                        .thenBy { it.studentId },
                )

        val reservedSeatCount = accountSummaries.sumOf(TodayAccountOverview::reservedSeatCount)
        val signedInSeatCount = accountSummaries.sumOf(TodayAccountOverview::signedInSeatCount)
        val waitingSignInSeatCount = accountSummaries.sumOf(TodayAccountOverview::waitingSignInCount)
        val reservationQueueCount = accountSummaries.sumOf(TodayAccountOverview::reservationQueueCount)
        val attentionCount = accountSummaries.sumOf(TodayAccountOverview::attentionCount)
        val completionPendingCount =
            accountSummaries.count { summary ->
                summary.waitingSignInCount > 0 ||
                    summary.reservationQueueCount > 0 ||
                    summary.attentionCount > 0 ||
                    (summary.reservedSeatCount > 0 && summary.signedInSeatCount < summary.reservedSeatCount)
            }
        val allSignedIn =
            accountSummaries.isNotEmpty() &&
                completionPendingCount == 0 &&
                reservedSeatCount > 0

        return TodayOverviewSnapshot(
            dateLabel = today.format(dateFormatter),
            totalAccountCount = accounts.size,
            reservationAccountCount = accountSummaries.size,
            totalTaskCount = accountSummaries.sumOf(TodayAccountOverview::totalTaskCount),
            reservedSeatCount = reservedSeatCount,
            signedInSeatCount = signedInSeatCount,
            waitingSignInSeatCount = waitingSignInSeatCount,
            reservationQueueCount = reservationQueueCount,
            attentionCount = attentionCount,
            allSignedIn = allSignedIn,
            signInHeadline =
                when {
                    accountSummaries.isEmpty() -> "今天暂无预约"
                    allSignedIn -> "今天全部完成签到"
                    else -> "还有 $completionPendingCount 个账号未完成"
                },
            signInDetail =
                when {
                    accountSummaries.isEmpty() -> "首页只统计今天的本地预约和签到记录。"
                    allSignedIn -> "已签到 $signedInSeatCount / $reservedSeatCount 个座位。"
                    else ->
                        buildList {
                            add("已签到 $signedInSeatCount / $reservedSeatCount")
                            if (waitingSignInSeatCount > 0) {
                                add("待签到 $waitingSignInSeatCount")
                            }
                            if (reservationQueueCount > 0) {
                                add("预约中 $reservationQueueCount")
                            }
                            if (attentionCount > 0) {
                                add("待处理 $attentionCount")
                            }
                        }.joinToString("，")
                },
            accountSummaries = accountSummaries,
        )
    }

    private fun List<ReservationTaskEntity>.toCounters(): TodayAccountCounters =
        TodayAccountCounters(
            totalTaskCount = size,
            reservedSeatCount = count { task -> task.countsAsReservedSeat() },
            signedInSeatCount = count { task -> task.state == ReservationTaskState.SIGNIN_SUCCESS },
            waitingSignInCount = count { task -> task.state in waitingSignInStates },
            reservationQueueCount = count { task -> task.state in reservationQueueStates },
            attentionCount = count { task -> task.state in attentionStates },
        )

    private fun TodayAccountCounters.mergeSnapshot(snapshot: SeatDisplaySnapshotEntity?): TodayAccountCounters {
        val liveState = snapshot?.toLiveState() ?: return this
        return when (liveState) {
            SeatBookingLiveState.RESERVED_WAITING_SIGNIN ->
                copy(
                    reservedSeatCount = maxOf(reservedSeatCount, 1),
                    waitingSignInCount = maxOf(waitingSignInCount, 1),
                )
            SeatBookingLiveState.ACTIVE_SIGNED_IN -> {
                val adjustedWaiting =
                    if (signedInSeatCount == 0 && waitingSignInCount > 0) {
                        waitingSignInCount - 1
                    } else {
                        waitingSignInCount
                    }
                copy(
                    reservedSeatCount = maxOf(reservedSeatCount, 1),
                    signedInSeatCount = maxOf(signedInSeatCount, 1),
                    waitingSignInCount = adjustedWaiting,
                )
            }
            SeatBookingLiveState.NEED_LOGIN,
            SeatBookingLiveState.IDLE,
            SeatBookingLiveState.FINISHED_OR_HISTORY,
            -> this
        }
    }

    private fun TodayAccountCounters.hasTodayData(): Boolean =
        totalTaskCount > 0 ||
            reservedSeatCount > 0 ||
            signedInSeatCount > 0 ||
            waitingSignInCount > 0 ||
            reservationQueueCount > 0 ||
            attentionCount > 0

    private fun TodayAccountCounters.toAccountSummary(studentId: String): TodayAccountOverview {
        val statusText =
            when {
                attentionCount > 0 -> "有 $attentionCount 个任务需要处理"
                waitingSignInCount > 0 -> "有 $waitingSignInCount 个座位待签到"
                reservationQueueCount > 0 -> "有 $reservationQueueCount 个预约处理中"
                reservedSeatCount > 0 && signedInSeatCount == reservedSeatCount -> "今天预约已全部签到"
                reservedSeatCount > 0 -> "已签到 $signedInSeatCount / $reservedSeatCount 个座位"
                else -> "今天有预约记录，但还没有成功占座"
            }

        return TodayAccountOverview(
            studentId = studentId,
            totalTaskCount = totalTaskCount,
            reservedSeatCount = reservedSeatCount,
            signedInSeatCount = signedInSeatCount,
            waitingSignInCount = waitingSignInCount,
            reservationQueueCount = reservationQueueCount,
            attentionCount = attentionCount,
            statusText = statusText,
        )
    }

    private fun SeatDisplaySnapshotEntity.toLiveState(): SeatBookingLiveState? =
        runCatching { SeatBookingLiveState.valueOf(liveState) }.getOrNull()

    private fun ReservationTaskEntity.countsAsReservedSeat(): Boolean {
        if (state == ReservationTaskState.CANCELLED) {
            return false
        }
        if (!bookingId.isNullOrBlank()) {
            return true
        }
        return state in bookedStatesWithoutId
    }

    private fun Long.isSameLocalDate(
        targetDate: LocalDate,
        zoneId: ZoneId,
    ): Boolean = Instant.ofEpochSecond(this).atZone(zoneId).toLocalDate() == targetDate

    private companion object {
        val reservationQueueStates =
            setOf(
                ReservationTaskState.PENDING_RESERVATION,
                ReservationTaskState.RESERVING,
                ReservationTaskState.FAILED_RETRYABLE,
            )

        val waitingSignInStates =
            setOf(
                ReservationTaskState.RESERVED_WAITING_SIGNIN,
                ReservationTaskState.GUARD_SCHEDULED,
                ReservationTaskState.SCANNING,
            )

        val attentionStates = setOf(ReservationTaskState.FAILED_MANUAL_ACTION)

        val bookedStatesWithoutId =
            setOf(
                ReservationTaskState.RESERVED_WAITING_SIGNIN,
                ReservationTaskState.GUARD_SCHEDULED,
                ReservationTaskState.SCANNING,
                ReservationTaskState.SIGNIN_SUCCESS,
                ReservationTaskState.FAILED_MANUAL_ACTION,
            )

        val dateFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA)
    }
}

private data class TodayAccountCounters(
    val totalTaskCount: Int = 0,
    val reservedSeatCount: Int = 0,
    val signedInSeatCount: Int = 0,
    val waitingSignInCount: Int = 0,
    val reservationQueueCount: Int = 0,
    val attentionCount: Int = 0,
)

data class TodayOverviewSnapshot(
    val dateLabel: String,
    val totalAccountCount: Int,
    val reservationAccountCount: Int,
    val totalTaskCount: Int,
    val reservedSeatCount: Int,
    val signedInSeatCount: Int,
    val waitingSignInSeatCount: Int,
    val reservationQueueCount: Int,
    val attentionCount: Int,
    val allSignedIn: Boolean,
    val signInHeadline: String,
    val signInDetail: String,
    val accountSummaries: List<TodayAccountOverview>,
)

data class TodayAccountOverview(
    val studentId: String,
    val totalTaskCount: Int,
    val reservedSeatCount: Int,
    val signedInSeatCount: Int,
    val waitingSignInCount: Int,
    val reservationQueueCount: Int,
    val attentionCount: Int,
    val statusText: String,
)
