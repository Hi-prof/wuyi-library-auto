package com.wuyi.libraryauto.core.ble

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BleScanResultTest {
    @Test
    fun `scan result only keeps seen minors observation`() {
        val result = BleScanResult(
            seenMinors = listOf(12, 58),
        )

        assertThat(result.seenMinors).containsExactly(12, 58).inOrder()
    }
}
