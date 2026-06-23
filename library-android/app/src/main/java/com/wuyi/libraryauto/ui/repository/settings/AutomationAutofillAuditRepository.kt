package com.wuyi.libraryauto.ui.repository.settings

import java.util.concurrent.atomic.AtomicReference

/**
 * 自动任务"自动填入"审计的轻量内存仓库。
 *
 * 不持久化、不接 Dao、不接 SharedPreferences；仅用作 [DiagnosticsLogRepository] 的辅助通道，
 * 满足 spec android-account-detail-and-batch-actions Property 20 / 21 的字段集合校验需求。
 */
class AutomationAutofillAuditRepository {
    private val latest = AtomicReference<AutomationAutofillAuditRecord?>(null)

    /**
     * 写入一条最新的自动填入审计；后写覆盖前写。
     */
    fun record(record: AutomationAutofillAuditRecord) {
        latest.set(record)
    }

    fun loadLatest(): AutomationAutofillAuditRecord? = latest.get()

    fun clear() {
        latest.set(null)
    }
}

/**
 * 审计落库字段白名单。
 *
 * 显式不包含 password / token / cookie / session 等凭证字段；message / errorMessage 由调用方在写入前完成脱敏。
 */
data class AutomationAutofillAuditRecord(
    val recordedAtEpochSeconds: Long,
    val studentId: String,
    val source: String,
    val roomName: String,
    val seatNumber: String,
    val level: AutofillAuditLevel,
    val errorClass: String? = null,
    val errorMessage: String? = null,
)
