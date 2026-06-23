package com.wuyi.libraryauto.core.network.seat

import java.net.URI

object ReservationRepository {

    fun buildBookApiUrl(searchApiUrl: String): String {
        val parsed = URI(searchApiUrl)
        require(parsed.path == SchoolSeatApi.SEARCH_SEATS_PATH) {
            "仅支持从 ${SchoolSeatApi.SEARCH_SEATS_PATH} 转换预约接口，当前 path=${parsed.path}"
        }
        return URI(
            parsed.scheme,
            parsed.rawAuthority,
            SchoolSeatApi.BOOK_SEATS_PATH,
            SchoolSeatApi.LAB_JSON_QUERY,
            null,
        ).toString()
    }
}
