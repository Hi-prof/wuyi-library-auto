package com.wuyi.libraryauto.ui.repository.settings

import com.wuyi.libraryauto.core.storage.db.ExecutionLogDao
import com.wuyi.libraryauto.core.storage.db.ExecutionLogEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ExecutionLogItem(
    val id: Long,
    val timestampLabel: String,
    val stateLabel: String,
    val message: String,
)

class ExecutionLogRepository(
    private val executionLogDao: ExecutionLogDao,
) {
    suspend fun loadAll(): List<ExecutionLogItem> = executionLogDao.listAllNewestFirst().map(::toItem)

    suspend fun clearAll() {
        executionLogDao.clearAll()
    }

    fun buildLatestCopyText(logs: List<ExecutionLogItem>): String? = logs.firstOrNull()?.toCopyLine()

    fun buildAllCopyText(logs: List<ExecutionLogItem>): String? =
        logs.takeIf(List<ExecutionLogItem>::isNotEmpty)?.joinToString("\n\n") { it.toCopyLine() }

    private fun toItem(log: ExecutionLogEntity): ExecutionLogItem =
        ExecutionLogItem(
            id = log.id,
            timestampLabel =
                Instant.ofEpochSecond(log.recordedAtEpochSeconds)
                    .atZone(zoneId)
                    .format(timeFormatter),
            stateLabel = log.state.name,
            message = log.message?.trim().orEmpty().ifBlank { "无附加消息" },
        )

    private fun ExecutionLogItem.toCopyLine(): String = "$timestampLabel [$stateLabel] $message"

    private companion object {
        private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")
        private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
