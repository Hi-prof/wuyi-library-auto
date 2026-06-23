package com.wuyi.libraryauto.ui.repository.task

import com.wuyi.libraryauto.core.network.seat.SeatBookingLiveState

enum class AutomationTaskMode {
    CONTINUOUS,
    SINGLE_CUSTOM,
}

data class AutomationPlanDraft(
    val planId: String = "",
    val studentId: String,
    val roomName: String,
    val seatNumber: String,
    val mode: AutomationTaskMode,
    val singleDate: String? = null,
    val singleStartTime: String? = null,
    val singleEndTime: String? = null,
)

data class AutomationPlanRecord(
    val planId: String,
    val studentId: String,
    val roomName: String,
    val seatNumber: String,
    val mode: AutomationTaskMode,
    val singleDate: String? = null,
    val singleStartTime: String? = null,
    val singleEndTime: String? = null,
    val enabled: Boolean,
    val previewText: String,
    val lastResultMessage: String,
)

data class AccountCardStatus(
    val studentId: String,
    val preferredSeatLabel: String,
    val isCurrent: Boolean,
    val isAuthenticated: Boolean,
    val latestPlanMessage: String,
    val latestTaskState: String,
    val liveState: String = "idle",
    val statusLabel: String = "",
    val currentBookingLabel: String = "",
    val checkinWindowOpen: Boolean = false,
    val pendingTaskCount: Int = 0,
    val primaryAction: AccountSeatAction? = null,
    val secondaryAction: AccountSeatAction? = null,
)

data class StoredAccountSnapshot(
    val studentId: String,
    val password: String = "",
    val preferredRoomName: String = "",
    val preferredSeatNumber: String = "",
)

data class PreferredSeatUpdate(
    val studentId: String,
    val preferredRoomName: String,
    val preferredSeatNumber: String,
)

interface StoredAccountSource {
    fun readStoredAccounts(): List<StoredAccountSnapshot>
}

interface AccountPreferenceWriter {
    fun updatePreferredSeat(
        studentId: String,
        preferredRoomName: String,
        preferredSeatNumber: String,
    )
}

enum class AccountSeatAction {
    CheckIn,
    CancelBooking,
    Checkout,
}

data class AccountSeatActionExecutionResult(
    val message: String,
    val updatedSnapshot: SeatBookingSnapshotView = SeatBookingSnapshotView(),
    val signInError: com.wuyi.libraryauto.core.domain.model.SignInError? = null,
)

data class SeatBookingSnapshotView(
    val liveState: SeatBookingLiveState = SeatBookingLiveState.IDLE,
    val bookingId: String? = null,
    val roomName: String = "",
    val seatNumber: String = "",
    val beginLabel: String = "",
    val statusLabel: String = "",
    val checkinWindowOpen: Boolean = false,
)

interface AccountSeatActionExecutor {
    suspend fun loadSnapshot(studentId: String): SeatBookingSnapshotView

    /**
     * 一次拉取该账号当前全部「活跃预约」。
     *
     * 与 [loadSnapshot] 的差异：
     * - [loadSnapshot] 仅返回最相关的一条（待签到优先 -> 已签到 -> 其它），用于聚合卡片状态。
     * - 本方法保留服务端返回的全部活跃预约，调用方可对每条单独执行
     *   [performAction]（指定 bookingId）。
     *
     * 当账号没有任何活跃预约时返回空列表；登录态失效时返回单元素列表，
     * 元素的 `liveState` 为 [SeatBookingLiveState.NEED_LOGIN]。
     */
    suspend fun loadActiveBookings(studentId: String): List<SeatBookingSnapshotView>

    suspend fun performAction(
        studentId: String,
        action: AccountSeatAction,
        bookingId: String? = null,
    ): AccountSeatActionExecutionResult
}
