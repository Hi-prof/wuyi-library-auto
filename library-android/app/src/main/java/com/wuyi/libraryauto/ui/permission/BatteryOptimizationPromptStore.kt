package com.wuyi.libraryauto.ui.permission

import android.content.Context
import android.content.SharedPreferences

/**
 * 电池优化豁免强提示节流存储。
 *
 * 用途：记录上一次向用户展示"电池优化豁免"强提示的 UTC 秒级时间戳，避免在 24 小时
 * 内重复打扰用户。提示判定与回写时间戳由调用方按需触发；本类只负责持久化与节流计算。
 *
 * 持久化策略：沿用同包内 `SeatLookupAuditRepository` 等模块的 `SharedPreferences + Lazy`
 * 模式。仅存储非敏感的时间戳，不涉及账号、令牌、密码等隐私字段。
 *
 * 线程安全：所有读写均通过 `synchronized(lock)` 串行化，并依赖 `SharedPreferences` 自身
 * 的并发保证，可在主线程或后台线程任意调用。
 */
class BatteryOptimizationPromptStore(
    private val preferences: Lazy<SharedPreferences>,
) {
    /**
     * 默认构造：使用应用上下文初始化普通 `SharedPreferences`。
     *
     * @param context 任意 Context，内部统一通过 `applicationContext` 持有，避免 Activity 泄漏。
     * @param preferencesName 存储文件名，默认值与同模块其他存储一致使用 `library_auto_*` 前缀。
     */
    constructor(
        context: Context,
        preferencesName: String = DEFAULT_PREFERENCES_NAME,
    ) : this(
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        },
    )

    /**
     * 测试与依赖注入入口：直接复用一个已存在的 `SharedPreferences` 实例。
     */
    internal constructor(preferences: SharedPreferences) : this(lazyOf(preferences))

    private val lock = Any()

    /**
     * 判定当前时刻是否应再次展示强提示。
     *
     * 规则：
     * - 从未提示过（时间戳 ≤0）→ 返回 true。
     * - 距上次提示已满 24 小时（86400 秒）→ 返回 true。
     * - 否则 → 返回 false，由 UI 仅保留入口可见、不弹强提示。
     *
     * @param nowEpochSeconds 当前 UTC 秒级时间戳，由调用方注入便于单测与跨时区测试。
     * @return 是否允许触发强提示。
     */
    fun shouldPromptStrong(nowEpochSeconds: Long): Boolean =
        synchronized(lock) {
            val lastPromptEpochSeconds = preferences.value.getLong(KEY_LAST_PROMPT_EPOCH_SECONDS, 0L)
            if (lastPromptEpochSeconds <= 0L) {
                return@synchronized true
            }
            val elapsedSeconds = nowEpochSeconds - lastPromptEpochSeconds
            elapsedSeconds >= THROTTLE_WINDOW_SECONDS
        }

    /**
     * 记录本次已向用户展示强提示的时间戳。
     *
     * 仅写入有效的正整数；非正值（≤0）会被忽略，避免污染节流判定。
     *
     * @param nowEpochSeconds 本次提示发生时的 UTC 秒级时间戳。
     */
    fun markPrompted(nowEpochSeconds: Long) {
        if (nowEpochSeconds <= 0L) {
            return
        }
        synchronized(lock) {
            preferences.value.edit()
                .putLong(KEY_LAST_PROMPT_EPOCH_SECONDS, nowEpochSeconds)
                .apply()
        }
    }

    /**
     * 清空已记录的提示时间戳，下一次 `shouldPromptStrong` 将再次返回 true。主要用于测试与调试入口。
     */
    fun clear() {
        synchronized(lock) {
            preferences.value.edit()
                .remove(KEY_LAST_PROMPT_EPOCH_SECONDS)
                .apply()
        }
    }

    private companion object {
        private const val DEFAULT_PREFERENCES_NAME = "library_auto_battery_optimization_prompt"
        private const val KEY_LAST_PROMPT_EPOCH_SECONDS = "last_prompt_epoch_seconds"
        private const val THROTTLE_WINDOW_SECONDS = 24L * 60L * 60L
    }
}
