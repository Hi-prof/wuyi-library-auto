package com.wuyi.libraryauto.core.storage.db

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppDatabaseErrorReporterTest {

    @Test
    fun `openFailure keeps cause and exposes Chinese recovery message`() {
        val cause = IllegalStateException("migration failed")

        val error = AppDatabaseErrorReporter.openFailure(cause)

        assertThat(error).isInstanceOf(AppDatabaseOpenException::class.java)
        assertThat(error).hasCauseThat().isSameInstanceAs(cause)
        assertThat(error).hasMessageThat().contains("本地数据库升级失败")
        assertThat(error).hasMessageThat().contains("已保留原有数据")
        assertThat(error).hasMessageThat().contains("不会自动清空或重建用户表")
    }
}
