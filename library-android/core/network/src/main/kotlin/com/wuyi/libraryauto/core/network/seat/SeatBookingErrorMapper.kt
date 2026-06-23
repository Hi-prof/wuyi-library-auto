package com.wuyi.libraryauto.core.network.seat

import com.wuyi.libraryauto.core.domain.model.SignInError

internal object SeatBookingErrorMapper {
    fun fromMessage(
        httpStatus: Int,
        code: String?,
        message: String?,
    ): SignInError {
        if (httpStatus == 401 || httpStatus == 403) {
            return SignInError.ServerRejected
        }
        if (httpStatus == 408 || httpStatus >= 500) {
            return SignInError.NetworkError
        }

        val normalizedCode = code.orEmpty().trim()
        val normalizedMessage = message.orEmpty().trim()
        if (normalizedCode.isBlank() && normalizedMessage.isBlank()) {
            return SignInError.Unknown
        }

        val text = "$normalizedCode $normalizedMessage"
        return when {
            normalizedCode in alreadySignedInStatusCodes ||
                text.containsAny("已签到", "已签过", "重复签到") ->
                SignInError.AlreadySignedIn

            normalizedCode in notInWindowStatusCodes ||
                text.containsAny("不在签到时间", "未到签到时间", "尚未到签到", "已过签到") ->
                SignInError.NotInWindow

            text.containsAny("请求过频", "稍后再试") ->
                SignInError.RateLimited

            normalizedCode == rejectedStatusCode ||
                text.containsAny("风控", "拒绝预约") ->
                SignInError.ServerRejected

            normalizedCode in finishedStatusCodes ||
                text.containsAny("已结束", "预约已结束", "已签退结束", "未归结束") ->
                SignInError.ServerRejected

            else -> SignInError.ServerRejected
        }
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { needle -> contains(needle, ignoreCase = true) }

    private val alreadySignedInStatusCodes = setOf("1", "2")
    private val notInWindowStatusCodes = setOf("5", "7")
    private val finishedStatusCodes = setOf("3", "6")
    private const val rejectedStatusCode = "9"
}
