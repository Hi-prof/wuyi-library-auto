package com.wuyi.libraryauto.ui.repository

import java.net.URI

object SchoolPortalConfig {
    const val LoginUrl = "https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list"
    const val DefaultManualSeatEntryUrl = LoginUrl

    val SeatServiceOrigin: String =
        URI(LoginUrl).run {
            URI(scheme, rawAuthority, null, null, null).toString()
        }

    val SeatEntryUrls: List<String> = listOf(DefaultManualSeatEntryUrl)
}
