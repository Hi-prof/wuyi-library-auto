package com.wuyi.libraryauto.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 同步按钮可用性状态指示器，仅暴露 [syncButtonState] 用于「Manual_Sync_Action 同步按钮」三态展示。
 *
 * 设计依据：
 * - design.md「Components and Interfaces · Android_Client」要求 `SyncStatusIndicator` 仅暴露
 *   [syncButtonState] Flow 用于同步按钮三态展示，**不再** 暴露任何可被 Composable / ViewModel
 *   用作「阻塞本地执行入口」的 Flow。
 * - Requirement 12.4 / 12.7 / 13.9：服务端连接状态指示 SHALL 不被解释为对本地执行流程的拒绝；
 *   非可达状态下 SHALL 仅置灰「同步」按钮本身，SHALL 不置灰任何与本地执行相关的入口。
 *
 * 状态模型：
 * - [SyncButtonState.Enabled]：Server_Sync_Config 已配置，且最近一次 sync 调用成功（或从未尝试
 *   但已配置 → 乐观默认可达，直到首次失败才降级）。
 * - [SyncButtonState.DisabledUnconfigured]：Server_Sync_Config 未配置（`base_url` 或
 *   `bearer_token` 缺失），客户端处于 Local_Only_Mode。
 * - [SyncButtonState.DisabledUnreachable]：Server_Sync_Config 已配置但最近一次 sync 调用失败
 *   （网络错误 / 401 / 426 / 5xx / 限频等）。
 *
 * 状态来源（不主动做网络探测）：
 * - 依赖最近一次 sync 调用的结果（成功 / 失败），由 Worker / ViewModel 在网络操作完成后调用
 *   [reportSuccess] 或 [reportFailure] 更新。
 * - 依赖 `Server_Sync_Config.isConfigured()` 的派生值，由 [updateConfigState] 在配置变更时调用。
 */
class SyncStatusIndicator(
    initialConfigured: Boolean = false,
) {
    private val _syncButtonState: MutableStateFlow<SyncButtonState> =
        MutableStateFlow(
            if (initialConfigured) SyncButtonState.Enabled else SyncButtonState.DisabledUnconfigured,
        )

    /**
     * 同步按钮可用性状态。Compose UI 仅对此 Flow 做 collectAsState：
     * - [SyncButtonState.Enabled] → 按钮可点击，文案「从服务端同步活跃池」。
     * - [SyncButtonState.DisabledUnconfigured] → 按钮置灰，文案「未配置服务端」。
     * - [SyncButtonState.DisabledUnreachable] → 按钮置灰，文案「服务端不可达」+ 最近错误提示。
     */
    val syncButtonState: StateFlow<SyncButtonState> = _syncButtonState.asStateFlow()

    /**
     * Worker / ViewModel 在 sync 调用成功后调用。
     * 仅在已配置状态下切到 [SyncButtonState.Enabled]；未配置状态不因 success 改变。
     */
    fun reportSuccess() {
        val current = _syncButtonState.value
        if (current != SyncButtonState.DisabledUnconfigured) {
            _syncButtonState.value = SyncButtonState.Enabled
        }
    }

    /**
     * Worker / ViewModel 在 sync 调用失败后调用（网络错误 / 401 / 426 / 5xx / 限频等）。
     * 仅在已配置状态下切到 [SyncButtonState.DisabledUnreachable]；未配置状态不因 failure 改变。
     *
     * @param reason 语义化原因字符串，便于 UI 展示具体错误提示（可选）。
     */
    fun reportFailure(reason: String = REASON_UNKNOWN) {
        val current = _syncButtonState.value
        if (current != SyncButtonState.DisabledUnconfigured) {
            _syncButtonState.value = SyncButtonState.DisabledUnreachable(reason = reason)
        }
    }

    /**
     * 把 [AccountPoolSyncResult] 翻译成 success/failure 更新。生产代码（Worker / Repository）
     * 调用 API 后一次性把结果交给 indicator，避免在每个分支重复调用 reportSuccess/reportFailure。
     */
    fun <T> reportSyncResult(result: AccountPoolSyncResult<T>) {
        when (result) {
            is AccountPoolSyncResult.Success -> reportSuccess()
            is AccountPoolSyncResult.Error -> reportFailure(reason = errorReasonOf(result))
        }
    }

    /**
     * 当 Server_Sync_Config 配置状态发生变更时调用（例如用户在设置页填入 / 清空 base_url）。
     *
     * @param configured true 表示 `base_url` 与 `bearer_token` 均非空（已配置）。
     */
    fun updateConfigState(configured: Boolean) {
        if (!configured) {
            _syncButtonState.value = SyncButtonState.DisabledUnconfigured
        } else {
            // 从未配置切到已配置：乐观默认为 enabled，直到首次 sync 失败才降级。
            val current = _syncButtonState.value
            if (current == SyncButtonState.DisabledUnconfigured) {
                _syncButtonState.value = SyncButtonState.Enabled
            }
            // 如果已经是 Enabled 或 DisabledUnreachable，保持不变（等待下次 sync 结果）。
        }
    }

    companion object {
        const val REASON_NETWORK: String = "network_error"
        const val REASON_HTTPS_REQUIRED: String = "https_required"
        const val REASON_UNAUTHORIZED: String = "unauthorized"
        const val REASON_RATE_LIMITED: String = "rate_limited"
        const val REASON_SERVER: String = "server_error"
        const val REASON_UNKNOWN: String = "unknown"

        /**
         * 进程级单例：Worker 与 ViewModel 共享同一份状态。
         */
        @Volatile
        private var defaultInstance: SyncStatusIndicator? = null

        fun default(): SyncStatusIndicator {
            val cached = defaultInstance
            if (cached != null) return cached
            return synchronized(this) {
                val existing = defaultInstance
                if (existing != null) {
                    existing
                } else {
                    val created = SyncStatusIndicator()
                    defaultInstance = created
                    created
                }
            }
        }

        /** 仅供测试覆盖默认实例。 */
        internal fun installDefaultForTesting(indicator: SyncStatusIndicator?) {
            defaultInstance = indicator
        }

        private fun errorReasonOf(error: AccountPoolSyncResult.Error): String =
            when (error) {
                is AccountPoolSyncResult.Error.Network -> REASON_NETWORK
                is AccountPoolSyncResult.Error.HttpsRequired -> REASON_HTTPS_REQUIRED
                is AccountPoolSyncResult.Error.Unauthorized -> REASON_UNAUTHORIZED
                is AccountPoolSyncResult.Error.RateLimited -> REASON_RATE_LIMITED
                is AccountPoolSyncResult.Error.Server -> REASON_SERVER
                is AccountPoolSyncResult.Error.AccountNotInActivePool -> REASON_SERVER
                is AccountPoolSyncResult.Error.Unexpected -> REASON_UNKNOWN
            }
    }
}

/**
 * 同步按钮的三态。
 *
 * - [Enabled]：可点击，服务端已配置且最近一次交互成功（或未尝试过但已配置）。
 * - [DisabledUnconfigured]：置灰，未配置 Server_Sync_Config。
 * - [DisabledUnreachable]：置灰，已配置但服务端不可达；携带 [reason] 便于 UI 展示具体错误。
 */
sealed class SyncButtonState {
    data object Enabled : SyncButtonState()

    data object DisabledUnconfigured : SyncButtonState()

    data class DisabledUnreachable(val reason: String = "unknown") : SyncButtonState()
}
