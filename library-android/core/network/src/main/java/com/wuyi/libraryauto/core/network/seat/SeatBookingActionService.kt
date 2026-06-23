package com.wuyi.libraryauto.core.network.seat

import com.wuyi.libraryauto.core.domain.model.SignInError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class SeatBookingActionResult(
    val bookingId: String,
    val httpStatus: Int,
    val rawMessage: String,
    val signInError: SignInError?,
)

open class SeatBookingActionService(
    private val api: SchoolSeatApi,
) {
    private val json = Json { ignoreUnknownKeys = true }

    open fun checkIn(
        url: String,
        bookingId: String,
    ): SeatBookingActionResult = execute(bookingId) { api.checkIn(url) }

    open fun checkout(
        url: String,
        bookingId: String,
    ): SeatBookingActionResult = execute(bookingId) { api.checkout(url) }

    open fun cancelBooking(
        url: String,
        bookingId: String,
    ): SeatBookingActionResult =
        execute(
            bookingId = bookingId,
            successMessages = CANCEL_SUCCESS_MESSAGES,
        ) {
            api.cancelBooking(url)
        }

    private fun execute(
        bookingId: String,
        successMessages: List<String> = emptyList(),
        call: () -> String,
    ): SeatBookingActionResult {
        val rawResponse = call()
        if (rawResponse.looksLikeHtml()) {
            return buildUnknownResult(bookingId, rawResponse)
        }
        val payload =
            runCatching { json.parseToJsonElement(rawResponse) }.getOrElse { error ->
                return buildUnknownResult(bookingId, rawResponse, error)
            }
        if (payload is JsonPrimitive) {
            return buildPrimitiveResult(
                bookingId = bookingId,
                payload = payload,
                successMessages = successMessages,
            )
        }
        val payloadObject = payload as? JsonObject
            ?: return buildUnknownResult(bookingId, payload.toString())

        val code = payloadObject.optionalText("CODE")
        val data = payloadObject.optionalObject("DATA")
        val rawMessage = data?.optionalText("msg")
            ?: payloadObject.optionalText("MESSAGE", "message")
            ?: "操作失败"
        val statusOrCode = data?.optionalText("status", "code", "result")
            ?: payloadObject.optionalText("status", "code")
            ?: code

        if (!code.equals("ok", ignoreCase = true)) {
            return buildBusinessFailure(
                bookingId = bookingId,
                code = statusOrCode,
                message = rawMessage,
            )
        }

        if (data == null) {
            return buildSuccessResult(
                bookingId = bookingId,
                message = payloadObject.optionalText("MESSAGE", "message") ?: "操作成功",
            )
        }
        if (!data.isSuccessful(rawMessage, successMessages)) {
            return buildBusinessFailure(
                bookingId = bookingId,
                code = statusOrCode,
                message = rawMessage,
            )
        }
        return buildSuccessResult(bookingId, rawMessage)
    }

    private fun buildPrimitiveResult(
        bookingId: String,
        payload: JsonPrimitive,
        successMessages: List<String>,
    ): SeatBookingActionResult {
        val message = payload.content.trim()
        if (!isSuccessfulResult(message, successMessages)) {
            return buildBusinessFailure(
                bookingId = bookingId,
                code = null,
                message = message.ifBlank { "操作失败" },
            )
        }
        return buildSuccessResult(bookingId, message.ifBlank { "操作成功" })
    }

    private fun JsonObject.optionalObject(key: String): JsonObject? = get(key) as? JsonObject

    private fun JsonObject.optionalText(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            (get(key) as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotBlank)
        }

    private fun JsonObject.isSuccessful(
        message: String,
        successMessages: List<String>,
    ): Boolean = isSuccessfulResult(optionalText("result").orEmpty(), successMessages, message)

    private fun isSuccessfulResult(
        result: String,
        successMessages: List<String>,
        message: String = result,
    ): Boolean {
        if (result.lowercase() in SUCCESSFUL_RESULTS) {
            return true
        }
        return successMessages.any(message::startsWith)
    }

    private fun String.looksLikeHtml(): Boolean {
        val trimmed = trim()
        if (!trimmed.startsWith("<")) {
            return false
        }
        val lowerCase = trimmed.lowercase()
        return lowerCase.contains("<html") ||
            lowerCase.contains("<!doctype html") ||
            lowerCase.contains("/user/index/login")
    }

    private fun buildSuccessResult(
        bookingId: String,
        message: String,
    ): SeatBookingActionResult =
        SeatBookingActionResult(
            bookingId = bookingId,
            httpStatus = HTTP_OK,
            rawMessage = message,
            signInError = null,
        )

    private fun buildBusinessFailure(
        bookingId: String,
        code: String?,
        message: String,
    ): SeatBookingActionResult =
        SeatBookingActionResult(
            bookingId = bookingId,
            httpStatus = HTTP_OK,
            rawMessage = message,
            signInError = SeatBookingErrorMapper.fromMessage(
                httpStatus = HTTP_OK,
                code = code,
                message = message,
            ),
        )

    private fun buildUnknownResult(
        bookingId: String,
        rawResponse: String,
        error: Throwable? = null,
    ): SeatBookingActionResult {
        val snippet = rawResponse.trim().replace("\n", " ").replace("\r", " ").take(120)
        return SeatBookingActionResult(
            bookingId = bookingId,
            httpStatus = HTTP_OK,
            rawMessage = snippet.ifBlank { error?.message?.take(120) ?: "空响应" },
            signInError = SignInError.Unknown,
        )
    }

    private companion object {
        private const val HTTP_OK = 200
        private val CANCEL_SUCCESS_MESSAGES = listOf("取消预约成功", "取消成功", "已取消预约")
        private val SUCCESSFUL_RESULTS = setOf("success", "ok", "true", "1")
    }
}
