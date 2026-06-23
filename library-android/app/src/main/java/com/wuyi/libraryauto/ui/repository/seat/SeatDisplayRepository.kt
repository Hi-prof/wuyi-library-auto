package com.wuyi.libraryauto.ui.repository.seat

import com.wuyi.libraryauto.core.network.seat.SeatBookingLiveState
import com.wuyi.libraryauto.core.storage.db.ReservationTaskDao
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import com.wuyi.libraryauto.core.storage.db.SeatDisplaySnapshotDao
import com.wuyi.libraryauto.core.storage.db.SeatDisplaySnapshotEntity
import com.wuyi.libraryauto.ui.repository.session.SessionRepository
import com.wuyi.libraryauto.ui.repository.task.AccountSeatAction
import com.wuyi.libraryauto.ui.repository.task.AccountSeatActionExecutor
import com.wuyi.libraryauto.ui.repository.task.SeatBookingNeedLoginException
import com.wuyi.libraryauto.ui.repository.task.SeatBookingSnapshotView
import com.wuyi.libraryauto.ui.screen.seat.SeatDisplayCardUiState
import com.wuyi.libraryauto.ui.screen.seat.toChineseLabel
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountEntry
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SeatDisplayRepository(
    private val accountRepository: SavedAccountRepository,
    private val sessionRepository: SessionRepository,
    private val reservationTaskDao: ReservationTaskDao,
    private val seatDisplaySnapshotDao: SeatDisplaySnapshotDao,
    private val accountSeatActionExecutor: AccountSeatActionExecutor?,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun fetchSnapshot(studentId: String): SeatDisplayCardUiState {
        val safeStudentId = studentId.trim()
        val account =
            accountRepository.readAll().firstOrNull { account -> account.studentId == safeStudentId }
                ?: return failureCard(safeStudentId, "未找到账号")
        val cachedCard = account.toCachedCard(readLatestTask(safeStudentId), readCachedSnapshot(safeStudentId))
        val executor = accountSeatActionExecutor
            ?: return cachedCard.copy(failureMessage = "座位状态服务不可用", statusLabel = "座位状态服务不可用")

        return runCatching {
            executor.loadSnapshot(safeStudentId)
        }.fold(
            onSuccess = { snapshot -> cachedCard.mergeSnapshot(snapshot).saveSnapshot() },
            onFailure = { error ->
                val message = error.message?.takeIf(String::isNotBlank) ?: "座位状态刷新失败，请稍后重试。"
                cachedCard.copy(
                    statusLabel = message,
                    failureMessage = message,
                    lastUpdatedEpochMillis = clockMillis(),
                )
            },
        )
    }

    /**
     * 「刷新全部」检测到待签到时由 ViewModel 调用：
     * 直接复用 [AccountSeatActionExecutor.performAction] 走签到通道，
     * 借助 AccountOperationCoordinator 做账号级互斥，避免与周期 worker 撞同一 booking。
     * 成功后再拉一次 snapshot 写回缓存，让 UI 显示最新状态。
     *
     * 软失败（预约在刷新到签到之间已消失/被结束/服务端实际已签到）不写入 failure：
     * 直接重新拉一次 snapshot 让 UI 同步到真实状态，避免把"刚才还在但现在没了"展示成红色错误。
     * 当未注入 executor 时，返回带失败原因的卡片但不抛错。
     */
    suspend fun signInWaitingCard(card: SeatDisplayCardUiState): SeatDisplayCardUiState {
        val executor = accountSeatActionExecutor
            ?: return card.copy(
                statusLabel = "座位状态服务不可用",
                failureMessage = "座位状态服务不可用",
                lastUpdatedEpochMillis = clockMillis(),
            )
        return runCatching { executor.performAction(card.studentId, AccountSeatAction.CheckIn) }
            .fold(
                onSuccess = { result ->
                    val merged = card.mergeSnapshot(result.updatedSnapshot)
                    val refreshed =
                        if (merged.liveState == SeatBookingLiveState.RESERVED_WAITING_SIGNIN) {
                            // 成功后接口偶尔仍返回旧状态，再拉一次确保 UI 立刻翻成「已签到」。
                            runCatching { executor.loadSnapshot(card.studentId) }
                                .fold(
                                    onSuccess = { snapshot -> card.mergeSnapshot(snapshot) },
                                    onFailure = { merged },
                                )
                        } else {
                            merged
                        }
                    refreshed.saveSnapshot()
                },
                onFailure = { error ->
                    if (error.isBookingNoLongerActionable()) {
                        // 预约已被服务端结束 / 刚才那条 bookingId 已不存在，重新拉一次 snapshot 让 UI 同步真实状态。
                        runCatching { executor.loadSnapshot(card.studentId) }
                            .fold(
                                onSuccess = { snapshot -> card.mergeSnapshot(snapshot).saveSnapshot() },
                                onFailure = {
                                    // 二次拉取也失败时，按普通失败处理但保留语义化文案。
                                    val message = error.message?.takeIf(String::isNotBlank)
                                        ?: "预约已结束，无法签到。"
                                    card.copy(
                                        statusLabel = message,
                                        failureMessage = message,
                                        lastUpdatedEpochMillis = clockMillis(),
                                    )
                                },
                            )
                    } else {
                        val message = error.message?.takeIf(String::isNotBlank) ?: "自动签到失败，请稍后重试。"
                        card.copy(
                            statusLabel = message,
                            failureMessage = message,
                            lastUpdatedEpochMillis = clockMillis(),
                        )
                    }
                },
            )
    }

    /**
     * 判断签到失败是否属于「预约已不可操作」：
     * - 服务端把会话识别为未登录由专用异常 [SeatBookingNeedLoginException] 表示，
     *   该路径会触发自动重新登录，落到这里前已被处理；为保险起见也归到软失败。
     * - 没有可操作预约或对应 bookingId 找不到时，AccountSeatActionRepository 会抛
     *   IllegalStateException("当前没有可操作预约") / IllegalStateException("未找到对应预约…")。
     * - normalizeSeatActionError 会把这些异常包装成「执行签到失败：…」，所以同时匹配包装文案。
     */
    private fun Throwable.isBookingNoLongerActionable(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is SeatBookingNeedLoginException) return true
            val message = current.message.orEmpty()
            if (message.contains("当前没有可操作预约") || message.contains("未找到对应预约")) {
                return true
            }
            current = current.cause
        }
        return false
    }

    suspend fun readCachedFromLocal(): List<SeatDisplayCardUiState> {
        val cachedSnapshots = runCatching { seatDisplaySnapshotDao.listAll().associateBy { it.studentId } }
        val cacheReadError = cachedSnapshots.exceptionOrNull()?.message?.takeIf(String::isNotBlank)
        return accountRepository.readAll().map { account ->
            val card =
                account.toCachedCard(
                    latestTask = readLatestTask(account.studentId),
                    cachedSnapshot = cachedSnapshots.getOrNull()?.get(account.studentId),
                )
            if (cacheReadError == null) {
                card
            } else {
                card.copy(
                    statusLabel = "读取本地座位状态失败",
                    failureMessage = cacheReadError,
                )
            }
        }
    }

    private suspend fun readLatestTask(studentId: String): ReservationTaskEntity? =
        runCatching { reservationTaskDao.findLatestForStudent(studentId) }.getOrNull()

    private suspend fun readCachedSnapshot(studentId: String): SeatDisplaySnapshotEntity? =
        runCatching { seatDisplaySnapshotDao.findByStudentId(studentId) }.getOrNull()

    private fun SavedAccountEntry.toCachedCard(
        latestTask: ReservationTaskEntity?,
        cachedSnapshot: SeatDisplaySnapshotEntity?,
    ): SeatDisplayCardUiState {
        val cachedLiveState = cachedSnapshot?.toLiveState()
        val fallbackRoomName = preferredRoomName.ifBlank { latestTask?.roomName.orEmpty() }
        val fallbackSeatNumber = preferredSeatNumber.ifBlank { latestTask?.seatNumber.orEmpty() }
        return SeatDisplayCardUiState(
            studentId = studentId,
            isCurrentSession = sessionRepository.currentSession(studentId) != null,
            roomName = cachedSnapshot?.roomName?.ifBlank { fallbackRoomName } ?: fallbackRoomName,
            seatNumber = cachedSnapshot?.seatNumber?.ifBlank { fallbackSeatNumber } ?: fallbackSeatNumber,
            beginLabel =
                cachedSnapshot?.beginLabel?.ifBlank {
                    latestTask?.startTimeEpochSeconds?.toBeginLabel().orEmpty()
                }
                ?: latestTask?.startTimeEpochSeconds?.toBeginLabel().orEmpty(),
            liveState = cachedLiveState ?: SeatBookingLiveState.IDLE,
            statusLabel =
                cachedSnapshot?.statusLabel?.ifBlank { cachedLiveState?.toChineseLabel().orEmpty() }
                    ?: latestTask?.state?.name
                    ?: SeatBookingLiveState.IDLE.toChineseLabel(),
            lastUpdatedEpochMillis = cachedSnapshot?.updatedAtEpochMillis?.takeIf { it > 0 } ?: clockMillis(),
        )
    }

    private fun SeatDisplayCardUiState.mergeSnapshot(snapshot: SeatBookingSnapshotView): SeatDisplayCardUiState =
        copy(
            roomName = snapshot.roomName.ifBlank { roomName },
            seatNumber = snapshot.seatNumber.ifBlank { seatNumber },
            beginLabel = snapshot.beginLabel.ifBlank { beginLabel },
            liveState = snapshot.liveState,
            statusLabel = snapshot.statusLabel.ifBlank { snapshot.liveState.toChineseLabel() },
            failureMessage = null,
            lastUpdatedEpochMillis = clockMillis(),
        )

    private suspend fun SeatDisplayCardUiState.saveSnapshot(): SeatDisplayCardUiState =
        runCatching { seatDisplaySnapshotDao.upsert(toSnapshotEntity()) }
            .fold(
                onSuccess = { this },
                onFailure = { error ->
                    copy(failureMessage = error.message?.takeIf(String::isNotBlank) ?: "本地座位状态保存失败")
                },
            )

    private fun SeatDisplayCardUiState.toSnapshotEntity(): SeatDisplaySnapshotEntity =
        SeatDisplaySnapshotEntity(
            studentId = studentId,
            roomName = roomName,
            seatNumber = seatNumber,
            beginLabel = beginLabel,
            liveState = liveState.name,
            statusLabel = statusLabel,
            updatedAtEpochMillis = lastUpdatedEpochMillis,
        )

    private fun SeatDisplaySnapshotEntity.toLiveState(): SeatBookingLiveState =
        runCatching { SeatBookingLiveState.valueOf(liveState) }.getOrDefault(SeatBookingLiveState.IDLE)

    private fun failureCard(
        studentId: String,
        message: String,
    ): SeatDisplayCardUiState =
        SeatDisplayCardUiState(
            studentId = studentId,
            statusLabel = message,
            failureMessage = message,
            lastUpdatedEpochMillis = clockMillis(),
        )

    private fun Long.toBeginLabel(): String =
        BEGIN_LABEL_FORMATTER.format(Instant.ofEpochSecond(this).atZone(SHANGHAI_ZONE))

    private companion object {
        private val SHANGHAI_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        private val BEGIN_LABEL_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    }
}
