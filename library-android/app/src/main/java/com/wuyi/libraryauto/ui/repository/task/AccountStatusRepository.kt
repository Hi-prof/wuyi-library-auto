package com.wuyi.libraryauto.ui.repository.task

import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import com.wuyi.libraryauto.core.network.seat.SeatBookingLiveState
import com.wuyi.libraryauto.core.storage.db.AutomationPlanDao
import com.wuyi.libraryauto.core.storage.db.ReservationTaskDao
import com.wuyi.libraryauto.ui.repository.session.SessionRepository
import kotlinx.coroutines.flow.first

class AccountStatusRepository(
    private val accountSource: StoredAccountSource,
    private val sessionRepository: SessionRepository,
    private val automationPlanDao: AutomationPlanDao,
    private val reservationTaskDao: ReservationTaskDao,
    private val accountSeatActionExecutor: AccountSeatActionExecutor? = null,
) {
    suspend fun load(): List<AccountCardStatus> {
        val latestPlans = automationPlanDao.observeAll().first()
        val allTasks = reservationTaskDao.listAll()
        val activeStudentId = sessionRepository.activeStudentId()
        return accountSource.readStoredAccounts().map { account ->
            val latestPlan = latestPlans.firstOrNull { it.studentId == account.studentId }
            val latestTask = reservationTaskDao.findLatestForStudent(account.studentId)
            val currentSession = sessionRepository.currentSession(account.studentId)
            val isAuthenticated = currentSession != null
            val snapshot =
                currentSession
                    ?.let {
                        runCatching {
                            accountSeatActionExecutor?.loadSnapshot(account.studentId)
                        }.getOrElse(::buildSnapshotLoadFailureView)
                    }
                    ?: SeatBookingSnapshotView(
                        liveState =
                            if (currentSession == null) {
                                SeatBookingLiveState.NEED_LOGIN
                            } else {
                                SeatBookingLiveState.IDLE
                            },
                        statusLabel =
                            if (currentSession == null) {
                                "需登录"
                            } else {
                                latestPlan?.lastResultMessage.orEmpty().ifBlank {
                                    latestTask?.state?.name ?: "暂无预约"
                                }
                            },
                    )
            AccountCardStatus(
                studentId = account.studentId,
                preferredSeatLabel = buildPreferredSeatLabel(account),
                isCurrent = account.studentId == activeStudentId,
                isAuthenticated = isAuthenticated,
                latestPlanMessage = latestPlan?.lastResultMessage.orEmpty(),
                latestTaskState = latestTask?.state?.name.orEmpty(),
                liveState = snapshot.liveState.toCardState(),
                statusLabel = snapshot.statusLabel.ifBlank { buildStatusLabel(snapshot.liveState) },
                currentBookingLabel =
                    listOf(snapshot.roomName, snapshot.seatNumber, snapshot.beginLabel)
                        .filter(String::isNotBlank)
                        .joinToString(" / "),
                checkinWindowOpen = snapshot.checkinWindowOpen,
                pendingTaskCount =
                    allTasks.count { task ->
                        task.studentId == account.studentId && task.state in pendingStates
                    },
                primaryAction = buildPrimaryAction(snapshot.liveState),
                secondaryAction = buildSecondaryAction(snapshot.liveState),
            )
        }
    }

    private fun buildPreferredSeatLabel(account: StoredAccountSnapshot): String =
        listOf(account.preferredRoomName, account.preferredSeatNumber)
            .filter(String::isNotBlank)
            .joinToString(" / ")

    private fun buildSnapshotLoadFailureView(error: Throwable): SeatBookingSnapshotView =
        SeatBookingSnapshotView(
            // 这里代表“预约状态查询失败”，不是“登录态丢失”，否则账号列表会把已认证账号误导成未登录。
            liveState = SeatBookingLiveState.IDLE,
            statusLabel =
                error.message?.takeIf(String::isNotBlank)
                    ?: "已认证，但当前座位状态获取失败，请稍后刷新。",
        )

    private fun buildPrimaryAction(liveState: SeatBookingLiveState): AccountSeatAction? =
        when (liveState) {
            SeatBookingLiveState.RESERVED_WAITING_SIGNIN -> AccountSeatAction.CheckIn
            SeatBookingLiveState.ACTIVE_SIGNED_IN -> AccountSeatAction.Checkout
            else -> null
        }

    private fun buildSecondaryAction(liveState: SeatBookingLiveState): AccountSeatAction? =
        when (liveState) {
            SeatBookingLiveState.RESERVED_WAITING_SIGNIN -> AccountSeatAction.CancelBooking
            else -> null
        }

    private fun buildStatusLabel(liveState: SeatBookingLiveState): String =
        when (liveState) {
            SeatBookingLiveState.NEED_LOGIN -> "需登录"
            SeatBookingLiveState.IDLE -> "暂无预约"
            SeatBookingLiveState.RESERVED_WAITING_SIGNIN -> "待签到"
            SeatBookingLiveState.ACTIVE_SIGNED_IN -> "已签到"
            SeatBookingLiveState.FINISHED_OR_HISTORY -> "最近记录已结束"
        }

    private fun SeatBookingLiveState.toCardState(): String =
        when (this) {
            SeatBookingLiveState.NEED_LOGIN -> "need-login"
            SeatBookingLiveState.IDLE -> "idle"
            SeatBookingLiveState.RESERVED_WAITING_SIGNIN -> "reserved-waiting-signin"
            SeatBookingLiveState.ACTIVE_SIGNED_IN -> "active-signed-in"
            SeatBookingLiveState.FINISHED_OR_HISTORY -> "finished-or-history"
        }

    private companion object {
        private val pendingStates =
            setOf(
                ReservationTaskState.PENDING_RESERVATION,
                ReservationTaskState.RESERVING,
                ReservationTaskState.RESERVED_WAITING_SIGNIN,
                ReservationTaskState.GUARD_SCHEDULED,
                ReservationTaskState.SCANNING,
                ReservationTaskState.FAILED_RETRYABLE,
            )
    }
}
