package com.wuyi.libraryauto.ui.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SchoolPortalConfigTest {

    @Test
    fun `manual reservation defaults to login entry and keeps dynamic resolution`() {
        assertThat(SchoolPortalConfig.DefaultManualSeatEntryUrl)
            .isEqualTo(SchoolPortalConfig.LoginUrl)
        assertThat(SchoolPortalConfig.SeatEntryUrls)
            .containsExactly(SchoolPortalConfig.LoginUrl)
    }

    @Test
    fun `campus portal defaults to current sso eportal login entry`() {
        assertThat(SchoolPortalConfig.DefaultCampusPortalLoginPageUrl)
            .startsWith("https://sso.wuyiu.edu.cn/login?service=")
        assertThat(SchoolPortalConfig.DefaultCampusPortalLoginPageUrl)
            .contains("211.80.243.20%2Feportal%2Findex.jsp")
    }

    @Test
    fun `campus portal resolver replaces blank and legacy saved entries with current default`() {
        assertThat(SchoolPortalConfig.resolveCampusPortalLoginPageUrl(null))
            .isEqualTo(SchoolPortalConfig.DefaultCampusPortalLoginPageUrl)
        assertThat(SchoolPortalConfig.resolveCampusPortalLoginPageUrl(" "))
            .isEqualTo(SchoolPortalConfig.DefaultCampusPortalLoginPageUrl)
        assertThat(
            SchoolPortalConfig.resolveCampusPortalLoginPageUrl(
                "http://10.10.244.11/srun_portal_pc.php",
            ),
        ).isEqualTo(SchoolPortalConfig.DefaultCampusPortalLoginPageUrl)
    }

    @Test
    fun `campus portal resolver keeps custom saved entries`() {
        assertThat(SchoolPortalConfig.resolveCampusPortalLoginPageUrl(" https://example.edu/login "))
            .isEqualTo("https://example.edu/login")
    }
}
