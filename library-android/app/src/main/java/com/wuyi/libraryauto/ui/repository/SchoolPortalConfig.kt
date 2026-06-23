package com.wuyi.libraryauto.ui.repository

import java.net.URI

object SchoolPortalConfig {
    const val LoginUrl = "https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list"
    const val DefaultManualSeatEntryUrl = LoginUrl

    /**
     * 校园网认证（captive portal）登录页 URL。
     *
     * 武夷学院常见入口为校内 i-NET 服务器；学校升级或更换 URL 时，
     * 用户可在「校园网认证」设置页手动覆盖此默认值。
     */
    const val DefaultCampusPortalLoginPageUrl = "https://sso.wuyiu.edu.cn/login?service=http:%2F%2F211.80.243.20%2Feportal%2Findex.jsp%3Fwlanuserip%3D34fd7a4669f451342424dfe2fac75642%26wlanacname%3D18260f9e92a595cf0b700dd16e7cd5c0%26ssid%3D0634f2ecab0f382a%26nasip%3D84df5acf10ac2ccbba9c01d2c00ccfff%26mac%3Db620fdf109d0e2bec4fd5c782b76b250%26t%3Dwireless-v2%26url%3D709db9dc9ce334aa02a9e1ee58ba6fcf3bc3349e947ead368bdd021b808fdbac30c65edaa96b0727"

    val SeatServiceOrigin: String =
        URI(LoginUrl).run {
            URI(scheme, rawAuthority, null, null, null).toString()
        }

    val SeatEntryUrls: List<String> = listOf(DefaultManualSeatEntryUrl)

    fun resolveCampusPortalLoginPageUrl(savedUrl: String?): String {
        val trimmed = savedUrl?.trim().orEmpty()
        if (trimmed.isBlank() || trimmed in LegacyCampusPortalLoginPageUrls) {
            return DefaultCampusPortalLoginPageUrl
        }
        return trimmed
    }

    private val LegacyCampusPortalLoginPageUrls: Set<String> =
        setOf("http://10.10.244.11/srun_portal_pc.php")
}
