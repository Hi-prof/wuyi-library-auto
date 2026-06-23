package com.wuyi.libraryauto.sync

import com.wuyi.libraryauto.core.storage.db.AutomationPlanDao
import com.wuyi.libraryauto.core.storage.db.AutomationPlanEntity
import java.security.MessageDigest
import retrofit2.HttpException

/**
 * 自动任务批量上传到服务端的入口。语义对齐 Windows 端
 * `wuyi_seat_bot.web_sync_service.upload_local_automation_plans_to_server`：
 *
 * 1. 读取本地 [AutomationPlanDao.findAll]；
 * 2. 通过 [AccountPoolSyncRepository.refreshActiveList] 拉取最新 Active_Pool，
 *    按 `studentId` 匹配出服务端 `accountId`；
 * 3. 用 [AccountPoolSyncRepository.getActiveAccountDetail] 拉详情拿到该账号现有任务的 `revision`；
 * 4. 把每条本地计划转换为 [AutomationTaskUpsertRequest]，调用
 *    [AutomationTaskUploader.enqueueUpsert] 写入 [PendingTaskUploadDao] 队列并触发
 *    [AutomationTaskUploadWorker]；
 *
 * 设计约束：
 * - **不**直接发起 PUT 请求；只负责写队列。真正的网络上传由 Worker 在后台 FIFO 执行，
 *   保证「点完按钮就退出」、崩溃可恢复，与 Windows 端 web 路径的同步上传不同。
 * - 双开关守卫由 [ServerSyncConfig.isUploadEnabled] 统一兜底，未配置 / 未启用时直接
 *   返回 [UploadOutcome.Disabled]，不会触发任何网络。
 * - 服务端 `task_id` 取值规则与 Windows 端 `_automation_task_id_for_plan` 保持一致：
 *   本地 plan 已经是「服务端管理」格式（`server-task:{accountId}:{taskId}`）→ 复用其 taskId；
 *   否则用 `SHA-256(planId|account:room:seat) → 前 8 字节大端 → 取 62 bit + (1 shl 62)` 生成
 *   稳定的伪 ID，让重复点击「上传」对同一计划生成同一个 task_id，避免在服务端制造重复行。
 */
class AutomationPlanUploadService(
    private val serverSyncConfig: ServerSyncConfig,
    private val automationPlanDao: AutomationPlanDao,
    private val accountPoolSyncRepository: AccountPoolSyncRepository,
    private val uploader: AutomationTaskUploader,
) {
    /** 单条计划的处理结果：交给 UI 展示「成功 N 条 / 跳过 M 条 / 拒绝 K 条」。 */
    data class PlanItemResult(
        val planId: String,
        val studentId: String,
        val roomName: String,
        val seatNumber: String,
        val status: Status,
        val reason: String? = null,
    ) {
        enum class Status { ENQUEUED, REJECTED, SKIPPED }
    }

    /** 调用结果。 */
    sealed class UploadOutcome {
        /** 双开关未开（未配置 base_url/token，或 upload_enabled=false）。 */
        data object Disabled : UploadOutcome()

        /** 本地没有任何 plan，无须上传。 */
        data object Empty : UploadOutcome()

        /** 拉取 Active_Pool 失败：网络错误 / 鉴权失败 / 限频 / 服务端 5xx 等。 */
        data class ActivePoolFailed(val reason: String) : UploadOutcome()

        /** 已写入上传队列（部分项可能因账号缺失等原因被拒）。 */
        data class Completed(
            val enqueued: Int,
            val skipped: Int,
            val rejected: Int,
            val items: List<PlanItemResult>,
        ) : UploadOutcome()
    }

    suspend fun uploadAllPlans(): UploadOutcome {
        if (!serverSyncConfig.isUploadEnabled()) {
            return UploadOutcome.Disabled
        }
        val plans = automationPlanDao.findAll()
        if (plans.isEmpty()) {
            return UploadOutcome.Empty
        }
        val activeAccounts = when (val result = accountPoolSyncRepository.refreshActiveList()) {
            is AccountPoolSyncResult.Success -> result.value
            is AccountPoolSyncResult.Error -> return UploadOutcome.ActivePoolFailed(
                reason = errorReason(result),
            )
        }
        val accountIdByStudentId =
            activeAccounts.associate { entity -> entity.studentId to entity.accountId }
        // detail 缓存：同一 accountId 只拉一次；revision 用于 expected_revision 校验。
        val revisionByAccountAndTask = mutableMapOf<Pair<Long, Long>, Long>()
        val detailFetched = mutableSetOf<Long>()

        var enqueued = 0
        var skipped = 0
        var rejected = 0
        val items = mutableListOf<PlanItemResult>()

        for (plan in plans) {
            val studentId = plan.studentId.trim()
            if (studentId.isBlank()) {
                rejected += 1
                items.add(toItem(plan, PlanItemResult.Status.REJECTED, "missing_student_id"))
                continue
            }
            val accountId = accountIdByStudentId[studentId]
            if (accountId == null) {
                rejected += 1
                items.add(toItem(plan, PlanItemResult.Status.REJECTED, "account_not_in_active_pool"))
                continue
            }
            val taskId = automationTaskIdForPlan(plan)
            if (accountId !in detailFetched) {
                detailFetched += accountId
                when (val detail = accountPoolSyncRepository.getActiveAccountDetail(accountId)) {
                    is AccountPoolSyncResult.Success ->
                        for (task in detail.value.automationTasks) {
                            revisionByAccountAndTask[accountId to task.taskId] = task.revision
                        }

                    is AccountPoolSyncResult.Error -> {
                        // 详情取不到时按「新建」尝试：expected_revision=0；服务端若已有同 task_id
                        // 会以 409 revision_conflict 拒绝，由 Worker 写入 TaskUploadConflictDao
                        // 让冲突解决 UI 处理。
                    }
                }
            }
            val expectedRevision = revisionByAccountAndTask[accountId to taskId] ?: 0L
            val request = AutomationTaskUpsertRequest(
                roomName = plan.roomName,
                seatNumber = plan.seatNumber,
                mode = AutomationTaskMode.MANUAL,
                customWindows = buildCustomWindows(plan),
                enabled = plan.enabled,
                revision = expectedRevision,
            )
            val pendingId = try {
                uploader.enqueueUpsert(
                    accountId = accountId,
                    taskId = taskId,
                    request = request,
                )
            } catch (e: HttpException) {
                rejected += 1
                items.add(toItem(plan, PlanItemResult.Status.REJECTED, "http_${e.code()}"))
                continue
            } catch (e: Exception) {
                rejected += 1
                items.add(toItem(plan, PlanItemResult.Status.REJECTED, "enqueue_error:${e.javaClass.simpleName}"))
                continue
            }
            if (pendingId == AutomationTaskUploader.SKIPPED_PENDING_ID) {
                skipped += 1
                items.add(toItem(plan, PlanItemResult.Status.SKIPPED, "local_only_mode"))
            } else {
                enqueued += 1
                items.add(toItem(plan, PlanItemResult.Status.ENQUEUED, null))
            }
        }
        return UploadOutcome.Completed(
            enqueued = enqueued,
            skipped = skipped,
            rejected = rejected,
            items = items,
        )
    }

    private fun toItem(
        plan: AutomationPlanEntity,
        status: PlanItemResult.Status,
        reason: String?,
    ): PlanItemResult =
        PlanItemResult(
            planId = plan.planId,
            studentId = plan.studentId,
            roomName = plan.roomName,
            seatNumber = plan.seatNumber,
            status = status,
            reason = reason,
        )

    private fun buildCustomWindows(plan: AutomationPlanEntity): List<AutomationCustomWindowDto> {
        if (plan.mode != "SINGLE_CUSTOM") return emptyList()
        val date = plan.singleDate ?: return emptyList()
        val startHour = parseHour(plan.singleStartTime) ?: return emptyList()
        val endHour = parseHour(plan.singleEndTime) ?: return emptyList()
        if (endHour <= startHour) return emptyList()
        return listOf(
            AutomationCustomWindowDto(
                date = date,
                startHour = startHour,
                endHour = endHour,
            ),
        )
    }

    private fun parseHour(value: String?): Int? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        return text.substringBefore(":").toIntOrNull()?.takeIf { it in 0..23 }
    }

    /**
     * 与 Windows 端 `_automation_task_id_for_plan` 等价的 task_id 生成规则：
     * - 已是「服务端管理」格式（`server-task:{accountId}:{taskId}`）→ 复用 taskId；
     * - 否则用 plan_id 或 「account:room:seat」做 sha256，取前 8 字节大端、保留 62 bit 后再加
     *   `1 shl 62`，得到稳定的伪 task_id，确保同一 plan 多次上传不会在服务端产生重复行。
     */
    private fun automationTaskIdForPlan(plan: AutomationPlanEntity): Long {
        val parsed = parseServerTaskPlanId(plan.planId)
        if (parsed != null && parsed.second > 0L) {
            return parsed.second
        }
        val raw = plan.planId.takeIf { it.isNotBlank() }
            ?: "${plan.studentId}:${plan.roomName}:${plan.seatNumber}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        var head = 0L
        for (i in 0 until 8) {
            head = (head shl 8) or (digest[i].toLong() and 0xFF)
        }
        val masked = head and ((1L shl 62) - 1L)
        return masked or (1L shl 62)
    }

    private fun parseServerTaskPlanId(planId: String): Pair<Long, Long>? {
        val text = planId.trim()
        if (!text.startsWith(SERVER_TASK_PLAN_ID_PREFIX)) return null
        val rest = text.removePrefix(SERVER_TASK_PLAN_ID_PREFIX)
        val parts = rest.split(":")
        if (parts.size != 2) return null
        val accountId = parts[0].toLongOrNull() ?: return null
        val taskId = parts[1].toLongOrNull() ?: return null
        return accountId to taskId
    }

    private fun errorReason(error: AccountPoolSyncResult.Error): String =
        when (error) {
            is AccountPoolSyncResult.Error.HttpsRequired -> "https_required"
            is AccountPoolSyncResult.Error.Unauthorized -> "unauthorized"
            is AccountPoolSyncResult.Error.RateLimited -> "rate_limited"
            is AccountPoolSyncResult.Error.AccountNotInActivePool -> "account_not_found"
            is AccountPoolSyncResult.Error.Server -> "server_${error.statusCode}"
            is AccountPoolSyncResult.Error.Network -> "network_error"
            is AccountPoolSyncResult.Error.Unexpected -> "unexpected_error"
        }

    private companion object {
        const val SERVER_TASK_PLAN_ID_PREFIX: String = "server-task:"
    }
}
