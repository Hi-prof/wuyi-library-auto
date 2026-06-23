package com.wuyi.libraryauto.sync

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import org.junit.Test

/**
 * 任务 12.6 单元测试：SyncStatusIndicator 三态状态机。
 *
 * 这些用例不依赖 Robolectric/Compose，只验证：
 * - 默认 / `initialConfigured=false` 时 [SyncButtonState.DisabledUnconfigured]，
 *   `initialConfigured=true` 时 [SyncButtonState.Enabled]。
 * - reportSuccess / reportFailure / reportSyncResult 在「已配置」分支下正确切换三态。
 * - 「未配置」分支下 reportSuccess / reportFailure 不能把状态从
 *   [SyncButtonState.DisabledUnconfigured] 切走（避免 UI 误以为已配置）。
 * - updateConfigState 在 false → true 时把 unconfigured 升到 enabled，true → false 时强制
 *   切回 unconfigured；其它已配置状态保持不变。
 * - reportSyncResult 把 [AccountPoolSyncResult.Error] 各分支翻译成对应 reason。
 */
class SyncStatusIndicatorTest {
    @Test
    fun `initial state is disabled unconfigured by default`() {
        val indicator = SyncStatusIndicator()
        assertThat(indicator.syncButtonState.value).isEqualTo(SyncButtonState.DisabledUnconfigured)
    }

    @Test
    fun `initial state is enabled when initialConfigured true`() {
        val indicator = SyncStatusIndicator(initialConfigured = true)
        assertThat(indicator.syncButtonState.value).isEqualTo(SyncButtonState.Enabled)
    }

    @Test
    fun `reportSuccess switches configured indicator to enabled`() {
        val indicator = SyncStatusIndicator(initialConfigured = true)
        indicator.reportFailure(SyncStatusIndicator.REASON_NETWORK)
        assertThat(indicator.syncButtonState.value)
            .isInstanceOf(SyncButtonState.DisabledUnreachable::class.java)

        indicator.reportSuccess()

        assertThat(indicator.syncButtonState.value).isEqualTo(SyncButtonState.Enabled)
    }

    @Test
    fun `reportFailure switches configured indicator to unreachable with reason`() {
        val indicator = SyncStatusIndicator(initialConfigured = true)

        indicator.reportFailure(SyncStatusIndicator.REASON_RATE_LIMITED)

        val state = indicator.syncButtonState.value
        assertThat(state).isInstanceOf(SyncButtonState.DisabledUnreachable::class.java)
        assertThat((state as SyncButtonState.DisabledUnreachable).reason)
            .isEqualTo(SyncButtonState.DisabledUnreachable(SyncStatusIndicator.REASON_RATE_LIMITED).reason)
    }

    @Test
    fun `reportSuccess does not move state away from unconfigured`() {
        val indicator = SyncStatusIndicator(initialConfigured = false)

        indicator.reportSuccess()

        assertThat(indicator.syncButtonState.value).isEqualTo(SyncButtonState.DisabledUnconfigured)
    }

    @Test
    fun `reportFailure does not move state away from unconfigured`() {
        val indicator = SyncStatusIndicator(initialConfigured = false)

        indicator.reportFailure(SyncStatusIndicator.REASON_NETWORK)

        assertThat(indicator.syncButtonState.value).isEqualTo(SyncButtonState.DisabledUnconfigured)
    }

    @Test
    fun `updateConfigState true upgrades unconfigured to enabled`() {
        val indicator = SyncStatusIndicator(initialConfigured = false)

        indicator.updateConfigState(configured = true)

        assertThat(indicator.syncButtonState.value).isEqualTo(SyncButtonState.Enabled)
    }

    @Test
    fun `updateConfigState false forces unconfigured regardless of current state`() {
        val indicator = SyncStatusIndicator(initialConfigured = true)
        indicator.reportFailure(SyncStatusIndicator.REASON_SERVER)

        indicator.updateConfigState(configured = false)

        assertThat(indicator.syncButtonState.value).isEqualTo(SyncButtonState.DisabledUnconfigured)
    }

    @Test
    fun `updateConfigState true preserves existing unreachable state`() {
        val indicator = SyncStatusIndicator(initialConfigured = true)
        indicator.reportFailure(SyncStatusIndicator.REASON_HTTPS_REQUIRED)

        indicator.updateConfigState(configured = true)

        val state = indicator.syncButtonState.value
        assertThat(state).isInstanceOf(SyncButtonState.DisabledUnreachable::class.java)
        assertThat((state as SyncButtonState.DisabledUnreachable).reason)
            .isEqualTo(SyncStatusIndicator.REASON_HTTPS_REQUIRED)
    }

    @Test
    fun `reportSyncResult success becomes enabled`() {
        val indicator = SyncStatusIndicator(initialConfigured = true)
        indicator.reportFailure(SyncStatusIndicator.REASON_NETWORK)

        indicator.reportSyncResult(
            AccountPoolSyncResult.Success<List<Int>>(
                value = emptyList(),
                serverTime = "2026-04-26T08:30:00Z",
            ),
        )

        assertThat(indicator.syncButtonState.value).isEqualTo(SyncButtonState.Enabled)
    }

    @Test
    fun `reportSyncResult network error maps to network reason`() {
        val indicator = SyncStatusIndicator(initialConfigured = true)

        indicator.reportSyncResult(AccountPoolSyncResult.Error.Network(IOException("boom")))

        val state = indicator.syncButtonState.value as SyncButtonState.DisabledUnreachable
        assertThat(state.reason).isEqualTo(SyncStatusIndicator.REASON_NETWORK)
    }

    @Test
    fun `reportSyncResult unauthorized maps to unauthorized reason`() {
        val indicator = SyncStatusIndicator(initialConfigured = true)

        indicator.reportSyncResult(AccountPoolSyncResult.Error.Unauthorized(IOException("401")))

        val state = indicator.syncButtonState.value as SyncButtonState.DisabledUnreachable
        assertThat(state.reason).isEqualTo(SyncStatusIndicator.REASON_UNAUTHORIZED)
    }

    @Test
    fun `reportSyncResult https required maps to https reason`() {
        val indicator = SyncStatusIndicator(initialConfigured = true)

        indicator.reportSyncResult(AccountPoolSyncResult.Error.HttpsRequired(IOException("426")))

        val state = indicator.syncButtonState.value as SyncButtonState.DisabledUnreachable
        assertThat(state.reason).isEqualTo(SyncStatusIndicator.REASON_HTTPS_REQUIRED)
    }

    @Test
    fun `reportSyncResult rate limited maps to rate limited reason`() {
        val indicator = SyncStatusIndicator(initialConfigured = true)

        indicator.reportSyncResult(AccountPoolSyncResult.Error.RateLimited(IOException("429")))

        val state = indicator.syncButtonState.value as SyncButtonState.DisabledUnreachable
        assertThat(state.reason).isEqualTo(SyncStatusIndicator.REASON_RATE_LIMITED)
    }

    @Test
    fun `reportSyncResult server error maps to server reason`() {
        val indicator = SyncStatusIndicator(initialConfigured = true)

        indicator.reportSyncResult(
            AccountPoolSyncResult.Error.Server(IOException("500"), statusCode = 500),
        )

        val state = indicator.syncButtonState.value as SyncButtonState.DisabledUnreachable
        assertThat(state.reason).isEqualTo(SyncStatusIndicator.REASON_SERVER)
    }

    @Test
    fun `reportSyncResult unexpected maps to unknown reason`() {
        val indicator = SyncStatusIndicator(initialConfigured = true)

        indicator.reportSyncResult(AccountPoolSyncResult.Error.Unexpected(IllegalStateException("x")))

        val state = indicator.syncButtonState.value as SyncButtonState.DisabledUnreachable
        assertThat(state.reason).isEqualTo(SyncStatusIndicator.REASON_UNKNOWN)
    }

    @Test
    fun `default returns the same singleton instance`() {
        try {
            SyncStatusIndicator.installDefaultForTesting(null)
            val a = SyncStatusIndicator.default()
            val b = SyncStatusIndicator.default()
            assertThat(a).isSameInstanceAs(b)
        } finally {
            SyncStatusIndicator.installDefaultForTesting(null)
        }
    }
}
