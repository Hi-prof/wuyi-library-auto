package com.wuyi.libraryauto.core.network.seat

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.domain.model.SignInError
import org.junit.Test

class SeatBookingErrorMapperTest {
    @Test
    fun `fromMessage maps known school messages and status codes`() {
        val cases =
            listOf(
                Case(message = "已签到", expected = SignInError.AlreadySignedIn),
                Case(message = "重复签到", expected = SignInError.AlreadySignedIn),
                Case(code = "2", message = "暂离中", expected = SignInError.AlreadySignedIn),
                Case(message = "不在签到时间", expected = SignInError.NotInWindow),
                Case(message = "尚未到签到时间", expected = SignInError.NotInWindow),
                Case(code = "7", message = "已过签到时间", expected = SignInError.NotInWindow),
                Case(message = "请求过频，请稍后再试", expected = SignInError.RateLimited),
                Case(message = "命中风控，拒绝预约", expected = SignInError.ServerRejected),
                Case(code = "9", message = "拒绝预约", expected = SignInError.ServerRejected),
                Case(code = "3", message = "预约已结束", expected = SignInError.ServerRejected),
                Case(code = "6", message = "暂离未归结束", expected = SignInError.ServerRejected),
            )

        cases.forEach { case ->
            assertThat(
                SeatBookingErrorMapper.fromMessage(
                    httpStatus = 200,
                    code = case.code,
                    message = case.message,
                ),
            ).isEqualTo(case.expected)
        }
    }

    @Test
    fun `fromMessage maps authorization and malformed responses`() {
        assertThat(SeatBookingErrorMapper.fromMessage(httpStatus = 401, code = null, message = "Unauthorized"))
            .isEqualTo(SignInError.ServerRejected)
        assertThat(SeatBookingErrorMapper.fromMessage(httpStatus = 403, code = null, message = "Forbidden"))
            .isEqualTo(SignInError.ServerRejected)
        assertThat(SeatBookingErrorMapper.fromMessage(httpStatus = 200, code = null, message = null))
            .isEqualTo(SignInError.Unknown)
    }

    private data class Case(
        val code: String? = null,
        val message: String,
        val expected: SignInError,
    )
}
