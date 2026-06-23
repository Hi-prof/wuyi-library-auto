package com.wuyi.libraryauto.core.runtime.watchdog

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 看门狗状态机状态。
 *
 * 用于刻画"周期巡检/Watchdog/GuardWorker"是否处于稳定运行：
 *
 * - [Healthy]：上一轮所有关键 unique work 均已就绪。
 * - [Degraded]：本轮发现关键 unique work 缺失，[consecutiveMissCount] 表示
 *   连续多少轮触发都需要补排（1..3），到达阈值时升级为 [Backoff]。
 * - [Backoff]：连续 3 轮都需要补排后进入退避窗口，由 [backoffStartEpochSeconds]
 *   起算 [backoffDurationSeconds]（默认 12 小时），窗口内补排间隔从 6h 延长为 12h。
 */
sealed class WatchdogState {
    /** 关键任务全部就绪，无需特殊处理。 */
    object Healthy : WatchdogState()

    /**
     * 检测到补排，但尚未达到退避阈值。
     *
     * @property consecutiveMissCount 连续缺失次数，取值范围 1..3。
     */
    data class Degraded(val consecutiveMissCount: Int) : WatchdogState()

    /**
     * 已进入退避窗口，期间补排间隔为 12 小时。
     *
     * @property backoffStartEpochSeconds 退避开始时刻（Unix 秒）。
     * @property backoffDurationSeconds 退避总时长，默认 12 小时。
     */
    data class Backoff(
        val backoffStartEpochSeconds: Long,
        val backoffDurationSeconds: Long = DEFAULT_BACKOFF_SECONDS,
    ) : WatchdogState() {
        companion object {
            /** 默认退避时长 12 小时（R14.4）。 */
            const val DEFAULT_BACKOFF_SECONDS: Long = 12L * 3600L
        }
    }
}

interface WatchdogStateRepository {
    fun read(): WatchdogState

    fun update(state: WatchdogState)

    fun reset()
}

/**
 * 看门狗状态持久化存储。
 *
 * 使用 [EncryptedSharedPreferences]（与 `SavedAccountStore` 同一种 master key 创建方式）
 * 把 [WatchdogState] 落到 `watchdog_state` 文件，避免重启 App 后丢失"已经连续缺失 N 次"
 * 这类信息。
 *
 * 序列化方案（三个键）：
 * - [KEY_STATE_KIND]：`healthy` / `degraded` / `backoff`
 * - [KEY_MISS_COUNT]：在 `degraded` 时表示连续缺失次数
 * - [KEY_BACKOFF_START_SECONDS] / [KEY_BACKOFF_DURATION_SECONDS]：在 `backoff` 时表示
 *   退避起点与时长
 *
 * @constructor 主构造器接受由调用方提供的 [SharedPreferences] 工厂，方便单测注入。
 *   生产代码请使用 `WatchdogStateStore(context)` 这一便利构造器。
 */
class WatchdogStateStore(
    private val preferences: Lazy<SharedPreferences>,
) : WatchdogStateRepository {
    /**
     * 便利构造器，使用 [EncryptedSharedPreferences] + AES256_GCM master key
     * 创建以 [PREFERENCES_NAME] 为名的加密 prefs。
     *
     * @param context 任意 [Context]，内部会取 `applicationContext`。
     */
    constructor(context: Context) : this(
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            createPreferences(context.applicationContext)
        },
    )

    internal constructor(preferences: SharedPreferences) : this(lazyOf(preferences))

    /**
     * 读取当前看门狗状态。
     *
     * @return 之前持久化过的 [WatchdogState]；从未写入过则返回 [WatchdogState.Healthy]。
     */
    override fun read(): WatchdogState {
        val prefs = preferences.value
        return when (prefs.getString(KEY_STATE_KIND, null)) {
            KIND_DEGRADED -> {
                val miss = prefs.getInt(KEY_MISS_COUNT, 0)
                if (miss <= 0) WatchdogState.Healthy else WatchdogState.Degraded(miss)
            }
            KIND_BACKOFF -> {
                val start = prefs.getLong(KEY_BACKOFF_START_SECONDS, 0L)
                val duration = prefs.getLong(
                    KEY_BACKOFF_DURATION_SECONDS,
                    WatchdogState.Backoff.DEFAULT_BACKOFF_SECONDS,
                )
                if (start <= 0L) {
                    WatchdogState.Healthy
                } else {
                    WatchdogState.Backoff(start, duration)
                }
            }
            KIND_HEALTHY -> WatchdogState.Healthy
            else -> WatchdogState.Healthy
        }
    }

    /**
     * 写入新的看门狗状态。
     *
     * 写入时只保留与当前 [state] 类型相关的键，避免不同状态间互相污染。
     *
     * @param state 待写入的状态。
     */
    override fun update(state: WatchdogState) {
        val editor = preferences.value.edit()
            .remove(KEY_STATE_KIND)
            .remove(KEY_MISS_COUNT)
            .remove(KEY_BACKOFF_START_SECONDS)
            .remove(KEY_BACKOFF_DURATION_SECONDS)
        when (state) {
            is WatchdogState.Healthy -> {
                editor.putString(KEY_STATE_KIND, KIND_HEALTHY)
            }
            is WatchdogState.Degraded -> {
                editor
                    .putString(KEY_STATE_KIND, KIND_DEGRADED)
                    .putInt(KEY_MISS_COUNT, state.consecutiveMissCount)
            }
            is WatchdogState.Backoff -> {
                editor
                    .putString(KEY_STATE_KIND, KIND_BACKOFF)
                    .putLong(KEY_BACKOFF_START_SECONDS, state.backoffStartEpochSeconds)
                    .putLong(KEY_BACKOFF_DURATION_SECONDS, state.backoffDurationSeconds)
            }
        }
        editor.apply()
    }

    /**
     * 重置为 [WatchdogState.Healthy]。
     *
     * 等价于 `update(WatchdogState.Healthy)`，提供给"全部就绪"分支调用。
     */
    override fun reset() {
        update(WatchdogState.Healthy)
    }

    companion object {
        /** 加密 prefs 文件名。 */
        const val PREFERENCES_NAME: String = "watchdog_state"

        internal const val KEY_STATE_KIND = "state_kind"
        internal const val KEY_MISS_COUNT = "miss_count"
        internal const val KEY_BACKOFF_START_SECONDS = "backoff_start_seconds"
        internal const val KEY_BACKOFF_DURATION_SECONDS = "backoff_duration_seconds"

        internal const val KIND_HEALTHY = "healthy"
        internal const val KIND_DEGRADED = "degraded"
        internal const val KIND_BACKOFF = "backoff"

        private fun createPreferences(context: Context): SharedPreferences {
            val masterKey =
                MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFERENCES_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
