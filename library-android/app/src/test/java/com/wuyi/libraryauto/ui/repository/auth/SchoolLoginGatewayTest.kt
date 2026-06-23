package com.wuyi.libraryauto.ui.repository.auth

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.network.auth.toLoginErrorMessage
import com.wuyi.libraryauto.core.network.http.HttpRequestException
import org.junit.Test
import java.net.SocketTimeoutException

class SchoolLoginGatewayTest {

    @Test
    fun `toLoginErrorMessage extracts message from http response body`() {
        val error =
            HttpRequestException(
                url = "https://wuyiu.huitu.zhishulib.com/api/1/login",
                statusCode = 405,
                responseBody = """{"code":"updateUserCache !userInfo.rid","message":"updateUserCache !userInfo.rid"}""",
            )

        assertThat(error.toLoginErrorMessage())
            .isEqualTo("登录接口 /api/1/login 返回 HTTP 405：updateUserCache !userInfo.rid")
    }

    @Test
    fun `toLoginErrorMessage gives explicit hint for obsolete huitu endpoint`() {
        val error =
            HttpRequestException(
                url = "https://wuyiu.huitu.zhishulib.com/api/1/login",
                statusCode = 404,
                responseBody = """{"CODE":"NotFound"}""",
            )

        assertThat(error.toLoginErrorMessage())
            .isEqualTo("当前学校登录接口已失效（/api/1/login 返回 HTTP 404），请更新登录入口后重试。")
    }

    @Test
    fun `toLoginErrorMessage gives explicit hint for timeout`() {
        val error = SocketTimeoutException("timeout")

        assertThat(error.toLoginErrorMessage()).isEqualTo("登录请求超时，请检查网络或学校接口状态后重试。")
    }
}
