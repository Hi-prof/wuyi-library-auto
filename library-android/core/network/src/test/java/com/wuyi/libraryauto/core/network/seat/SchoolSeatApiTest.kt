package com.wuyi.libraryauto.core.network.seat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SchoolSeatApiTest {

    @Test
    fun `appendLabJson keeps pre-encoded search query intact`() {
        val rawUrl =
            "https://example.com/Seat/Index/searchSeats?" +
                "space_category%5Bcategory_id%5D=591&space_category%5Bcontent_id%5D=28"

        val actual = SchoolSeatApi.appendLabJson(rawUrl)

        assertThat(actual).isEqualTo("$rawUrl&LAB_JSON=1")
    }
}
