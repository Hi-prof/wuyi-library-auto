package com.wuyi.libraryauto.ui.repository

import java.net.URI

object SchoolPortalConfig {
    const val LoginUrl = "https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list"
    const val DefaultManualSeatEntryUrl = LoginUrl
    const val DefaultCampusPortalLoginPageUrl =
        "https://sso.wuyiu.edu.cn/login?service=http%3A%2F%2F211.80.243.20%2Feportal%2Findex.jsp"

    val SeatServiceOrigin: String =
        URI(LoginUrl).run {
            URI(scheme, rawAuthority, null, null, null).toString()
        }

    val SeatEntryUrls: List<String> = listOf(DefaultManualSeatEntryUrl)

    fun resolveCampusPortalLoginPageUrl(savedValue: String?): String {
        val value = savedValue?.trim().orEmpty()
        if (value.isBlank() || value == "http://10.10.244.11/srun_portal_pc.php") {
            return DefaultCampusPortalLoginPageUrl
        }
        return value
    }
}
