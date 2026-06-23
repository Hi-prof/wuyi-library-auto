package com.wuyi.libraryauto.ui.repository.settings

import com.wuyi.libraryauto.core.runtime.diagnostics.LocalDiagnosticLogRepository

data class DiagnosticsLogEntry(
    val sourceLabel: String,
    val timestampLabel: String,
    val title: String,
    val detailLines: List<String>,
)

data class DiagnosticsLogSnapshot(
    val entries: List<DiagnosticsLogEntry>,
)

enum class AutofillAuditLevel {
    INFO,
    WARN,
}

class DiagnosticsLogRepository(
    private val executionLogRepository: ExecutionLogRepository,
    private val loginAuditRepository: LoginAuditRepository,
    private val seatStatusAuditRepository: SeatStatusAuditRepository,
    private val seatLookupAuditRepository: SeatLookupAuditRepository,
    private val seatActionAuditRepository: SeatActionAuditRepository,
    private val localDiagnosticLogRepository: LocalDiagnosticLogRepository? = null,
    private val automationAutofillAuditRepository: AutomationAutofillAuditRepository =
        AutomationAutofillAuditRepository(),
) {
    /**
     * 写入一条"自动任务自动填入"审计记录。
     *
     * 字段白名单：仅写入 [studentId] / [source] / [roomName] / [seatNumber] / [errorClass] / [errorMessage]
     * 共 6 个字段；**显式不写入账号密码或会话凭证**——
     * 不读取 `StoredAccountSnapshot.password`、也不读取 `SessionRepository.currentSession(...)` 返回的任何字段
     * （token / cookie / session 等凭证均不在审计范围内）。
     *
     * - INFO：无 errorClass / errorMessage，记录正常自动填入路径；
     * - WARN：必带 errorClass / errorMessage，记录读取历史异常等回退场景。
     *
     * 写入前对 [errorMessage] 调用 [sanitizeForAudit] 去除潜在的
     * `token` / `cookie` / `session` / `password` / `authorization` 片段；
     * [source] / [roomName] / [seatNumber] 由调用方控制，不参与脱敏。
     */
    suspend fun recordAutomationAutofill(
        studentId: String,
        source: String,
        roomName: String,
        seatNumber: String,
        level: AutofillAuditLevel = AutofillAuditLevel.INFO,
        errorClass: String? = null,
        errorMessage: String? = null,
    ) {
        when (level) {
            AutofillAuditLevel.INFO ->
                require(errorClass == null && errorMessage == null) {
                    "INFO 级别审计不应携带 errorClass / errorMessage"
                }
            AutofillAuditLevel.WARN ->
                require(!errorClass.isNullOrBlank() && !errorMessage.isNullOrBlank()) {
                    "WARN 级别审计必须同时提供 errorClass 与 errorMessage"
                }
        }
        automationAutofillAuditRepository.record(
            AutomationAutofillAuditRecord(
                recordedAtEpochSeconds = System.currentTimeMillis() / 1000,
                studentId = studentId.trim(),
                source = source.trim(),
                roomName = roomName.trim(),
                seatNumber = seatNumber.trim(),
                level = level,
                errorClass = errorClass?.trim(),
                errorMessage = sanitizeForAudit(errorMessage?.trim()),
            ),
        )
    }

    /**
     * 对审计文本做凭证脱敏：
     * - 命中 `token` / `cookie` / `session` / `password` / `authorization` 关键字的整段
     *   （`key=value`、`key value`、`key:value` 三种形态）整体替换为 `***`；
     * - `null` 透传 `null`，空串原样返回。
     *
     * 仅用于 [recordAutomationAutofill] 的 `errorMessage` 兜底脱敏，
     * 不做更激进的内容修改，避免破坏调用方传入的合法堆栈信息。
     */
    private fun sanitizeForAudit(text: String?): String? {
        if (text == null) return null
        return text
            .replace(SENSITIVE_KEY_COLON_PATTERN, REDACTED_PLACEHOLDER)
            .replace(SENSITIVE_KEY_VALUE_PATTERN, REDACTED_PLACEHOLDER)
    }

    suspend fun loadSnapshot(): DiagnosticsLogSnapshot {
        val executionEntries =
            executionLogRepository.loadAll().map { item ->
                DiagnosticsLogEntry(
                    sourceLabel = "运行日志",
                    timestampLabel = item.timestampLabel,
                    title = item.stateLabel,
                    detailLines = listOf(item.message),
                )
            }
        val loginEntry =
            loginAuditRepository.loadLatest()?.let { audit ->
                DiagnosticsLogEntry(
                    sourceLabel = "登录诊断",
                    timestampLabel = audit.timestampLabel,
                    title = audit.outcomeLabel,
                    detailLines =
                        listOf(
                            "学号：${audit.studentId}",
                            "入口：${audit.loginUrl}",
                            "详情：${audit.message}",
                        ),
                )
            }
        val seatEntry =
            seatStatusAuditRepository.loadLatest()?.let { audit ->
                DiagnosticsLogEntry(
                    sourceLabel = "座位状态",
                    timestampLabel = audit.timestampLabel,
                    title = audit.outcomeLabel,
                    detailLines =
                        listOf(
                            "学号：${audit.studentId}",
                            "请求：${audit.requestUrl}",
                            "详情：${audit.message}",
                        ),
                )
            }
        val seatLookupEntry =
            seatLookupAuditRepository.loadLatest()?.let { audit ->
                DiagnosticsLogEntry(
                    sourceLabel = "手动查座",
                    timestampLabel = audit.timestampLabel,
                    title = audit.outcomeLabel,
                    detailLines =
                        listOf(
                            "学号：${audit.studentId}",
                            "入口：${audit.entryUrl}",
                            "详情：${audit.message}",
                        ),
                )
            }
        val seatActionEntry =
            seatActionAuditRepository.loadLatest()?.let { audit ->
                DiagnosticsLogEntry(
                    sourceLabel = "账号动作",
                    timestampLabel = audit.timestampLabel,
                    title = "${audit.actionLabel}${audit.outcomeLabel}",
                    detailLines =
                        listOf(
                            "学号：${audit.studentId}",
                            "动作：${audit.actionLabel}",
                            "请求：${audit.requestUrl.ifBlank { "前置步骤未生成请求地址" }}",
                            "详情：${audit.message}",
                        ),
                )
            }
        val localDiagnosticEntries =
            localDiagnosticLogRepository?.loadEntries().orEmpty().map { entry ->
                DiagnosticsLogEntry(
                    sourceLabel = "本机诊断",
                    timestampLabel = entry.timestampLabel,
                    title = "${entry.level} · ${entry.source} · ${entry.title}",
                    detailLines = entry.detailLines.ifEmpty { listOf("无附加信息") },
                )
            }
        return DiagnosticsLogSnapshot(
            entries =
                listOfNotNull(
                    seatActionEntry,
                    seatLookupEntry,
                    seatEntry,
                    loginEntry,
                    *executionEntries.toTypedArray(),
                    *localDiagnosticEntries.toTypedArray(),
                )
                    .sortedByDescending(DiagnosticsLogEntry::timestampLabel),
        )
    }

    fun buildCopyText(snapshot: DiagnosticsLogSnapshot): String? =
        snapshot.entries
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n") { entry ->
                buildString {
                    append("${entry.timestampLabel} [${entry.sourceLabel}] ${entry.title}")
                    entry.detailLines.forEach { detail ->
                        append("\n")
                        append(detail)
                    }
                }
            }

    suspend fun clearAll() {
        executionLogRepository.clearAll()
        loginAuditRepository.clear()
        seatStatusAuditRepository.clear()
        seatLookupAuditRepository.clear()
        seatActionAuditRepository.clear()
        localDiagnosticLogRepository?.clear()
        automationAutofillAuditRepository.clear()
    }

    private companion object {
        private const val REDACTED_PLACEHOLDER = "***"

        /**
         * 形如 `token=xxx` / `password = xxx` / `Authorization xxx` / `Cookie xxx` 的兜底匹配；
         * 与 design.md 中 P22 描述一致：把"凭证关键字 + 可选等号 + 值"整段替换为占位符。
         */
        private val SENSITIVE_KEY_VALUE_PATTERN =
            Regex("(?i)(token|cookie|session|password|authorization)\\s*=?\\s*[^\\s;,&]+")

        /**
         * 形如 `Cookie: value` / `password : xxx` 的冒号分隔形态。
         * `SENSITIVE_KEY_VALUE_PATTERN` 中 `\s*=?\s*` 不覆盖冒号，单独再扫一遍即可命中。
         */
        private val SENSITIVE_KEY_COLON_PATTERN =
            Regex("(?i)(token|cookie|session|password|authorization)\\s*:\\s*[^\\s;,&]+")
    }
}
