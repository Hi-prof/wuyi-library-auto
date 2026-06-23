package com.wuyi.libraryauto.ui.repository.task

import com.wuyi.libraryauto.core.storage.db.AutomationPlanDao
import com.wuyi.libraryauto.core.storage.db.ReservationTaskDao
import com.wuyi.libraryauto.core.storage.db.SeatDisplaySnapshotDao

/**
 * 单次历史命中。
 *
 * 用于 `Automation_Task_Dialog` 在切换学号时按"最近一次为先"地预填
 * `roomName` / `seatNumber`，以及在自习室下拉与可选座位区上做"曾用"标记。
 *
 * @param roomName 自习室名称（已 trim，非空）。
 * @param seatNumber 座位号（已 trim，非空）。
 * @param source 数据源标识，用于审计日志归档。
 * @param timestampEpochSeconds 统一时间戳（秒），用于段内倒序排序。
 */
data class ReservationHistoryHit(
    val roomName: String,
    val seatNumber: String,
    val source: HistorySource,
    val timestampEpochSeconds: Long,
)

/**
 * Account_Reservation_History 的来源枚举。
 *
 * 排序优先级：HISTORY_PLAN < RESERVATION_TASK < SEAT_SNAPSHOT < PREFERRED_SEAT，
 * 其中 HISTORY_PLAN 对应 `AutomationPlanDao`、RESERVATION_TASK 对应 `ReservationTaskDao`、
 * SEAT_SNAPSHOT 对应 `SeatDisplaySnapshotDao`、PREFERRED_SEAT 对应
 * `StoredSavedAccountRepository.preferredRoomName` / `preferredSeatNumber` 兜底字段。
 */
enum class HistorySource {
    HISTORY_PLAN,
    RESERVATION_TASK,
    SEAT_SNAPSHOT,
    PREFERRED_SEAT,
}

/**
 * 读取单账号 Account_Reservation_History。
 *
 * 实现侧需要按"优先级 1 → 4 串接，每段内部按时间戳倒序"的策略合并去重，
 * 并丢弃 roomName / seatNumber 为空的命中。具体实现由 1.3 的下游任务提供。
 */
interface AccountReservationHistoryReader {
    suspend fun loadHistory(studentId: String): List<ReservationHistoryHit>
}

/**
 * Account_Reservation_History 的默认实现。
 *
 * 数据源与排序口径（与 design.md 4.1 / 4.9 一致）：
 *   1. `AutomationPlanDao`：当前实现仅暴露 `findLatestEnabledByStudentId(studentId)`，按 `updatedAtEpochSeconds DESC`
 *      返回单条 enabled = 1 的最新计划，因此该段最多产出一个 `HISTORY_PLAN` 命中。
 *   2. `ReservationTaskDao.listForStudent(studentId)`：DAO 端已按 `startTimeEpochSeconds DESC` 排序，逐条映射为
 *      `RESERVATION_TASK`，`timestampEpochSeconds` 取 `startTimeEpochSeconds`（设计文档
 *      明确以"开始时间"代替需求文档中的"创建时间"语义）。
 *   3. `SeatDisplaySnapshotDao.findByStudentId(studentId)`：每个学号至多一条快照，`timestampEpochSeconds` 取
 *      `updatedAtEpochMillis / 1000` 折算到秒。
 *   4. `StoredAccountSource`：当 `StoredAccountSnapshot.preferredRoomName` 与 `preferredSeatNumber` 同时非空时，
 *      产出一条 `PREFERRED_SEAT` 兜底命中，`timestampEpochSeconds = 0L`。
 *
 * 合并策略：四段独立按时间戳倒序后串接（优先级 1 → 4，段内时间戳倒序），不跨段重排；
 * 按 `(roomName.trim(), seatNumber.trim())` 二元组去重，保留**先出现**（即更高优先级）的命中；
 * `roomName` / `seatNumber` trim 后为空的候选直接丢弃。
 *
 * 不发起任何网络调用；DAO / 仓库异常不在此处捕获，由调用方（如
 * `AutomationTaskViewModel.updateDialogStudentId`）按 P21 兜底为 WARN 日志。
 */
class AccountReservationHistoryReaderImpl(
    private val planDao: AutomationPlanDao,
    private val taskDao: ReservationTaskDao,
    private val snapshotDao: SeatDisplaySnapshotDao,
    private val storedAccountSource: StoredAccountSource,
) : AccountReservationHistoryReader {
    override suspend fun loadHistory(studentId: String): List<ReservationHistoryHit> {
        val planHits =
            planDao
                .findLatestEnabledByStudentId(studentId)
                ?.let { plan ->
                    listOf(
                        ReservationHistoryHit(
                            roomName = plan.roomName,
                            seatNumber = plan.seatNumber,
                            source = HistorySource.HISTORY_PLAN,
                            timestampEpochSeconds = plan.updatedAtEpochSeconds,
                        ),
                    )
                }
                .orEmpty()

        val taskHits =
            taskDao.listForStudent(studentId).map { task ->
                ReservationHistoryHit(
                    roomName = task.roomName,
                    seatNumber = task.seatNumber,
                    source = HistorySource.RESERVATION_TASK,
                    timestampEpochSeconds = task.startTimeEpochSeconds,
                )
            }

        val snapshotHits =
            snapshotDao
                .findByStudentId(studentId)
                ?.let { snapshot ->
                    listOf(
                        ReservationHistoryHit(
                            roomName = snapshot.roomName,
                            seatNumber = snapshot.seatNumber,
                            source = HistorySource.SEAT_SNAPSHOT,
                            timestampEpochSeconds = snapshot.updatedAtEpochMillis / 1000L,
                        ),
                    )
                }
                .orEmpty()

        val preferredHits =
            storedAccountSource
                .readStoredAccounts()
                .firstOrNull { it.studentId == studentId }
                ?.takeIf {
                    it.preferredRoomName.isNotBlank() && it.preferredSeatNumber.isNotBlank()
                }
                ?.let { account ->
                    listOf(
                        ReservationHistoryHit(
                            roomName = account.preferredRoomName,
                            seatNumber = account.preferredSeatNumber,
                            source = HistorySource.PREFERRED_SEAT,
                            timestampEpochSeconds = 0L,
                        ),
                    )
                }
                .orEmpty()

        val ordered =
            planHits +
                taskHits +
                snapshotHits +
                preferredHits

        val seen = HashSet<Pair<String, String>>()
        val result = ArrayList<ReservationHistoryHit>(ordered.size)
        for (hit in ordered) {
            val normalizedRoom = hit.roomName.trim()
            val normalizedSeat = hit.seatNumber.trim()
            if (normalizedRoom.isEmpty() || normalizedSeat.isEmpty()) {
                continue
            }
            val key = normalizedRoom to normalizedSeat
            if (seen.add(key)) {
                result.add(
                    hit.copy(
                        roomName = normalizedRoom,
                        seatNumber = normalizedSeat,
                    ),
                )
            }
        }
        return result
    }
}
