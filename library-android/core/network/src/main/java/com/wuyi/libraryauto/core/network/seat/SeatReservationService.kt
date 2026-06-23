package com.wuyi.libraryauto.core.network.seat

import com.wuyi.libraryauto.core.network.auth.SessionBundle
import com.wuyi.libraryauto.core.network.http.OkHttpSchoolHttpClient
import com.wuyi.libraryauto.core.network.http.SchoolHttpClient
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

data class ReservationReceipt(
    val bookingId: String,
    val message: String,
)

interface SeatReservationGateway {
    fun reserveSeat(
        searchApiUrl: String,
        session: SessionBundle,
        seatId: String,
        roomId: String,
        roomName: String,
        seatNumber: String,
        beginTime: Int,
        durationSeconds: Int,
    ): ReservationReceipt
}

class SeatReservationService(
    private val httpClient: SchoolHttpClient = OkHttpSchoolHttpClient(),
    private val apiTimeProvider: () -> Int = { (System.currentTimeMillis() / 1000).toInt() },
) : SeatReservationGateway {
    private val json = Json { ignoreUnknownKeys = true }

    override fun reserveSeat(
        searchApiUrl: String,
        session: SessionBundle,
        seatId: String,
        roomId: String,
        roomName: String,
        seatNumber: String,
        beginTime: Int,
        durationSeconds: Int,
    ): ReservationReceipt {
        val apiTime = apiTimeProvider()
        val requestUrl = ReservationRepository.buildBookApiUrl(searchApiUrl)
        val formFields =
            listOf(
                "beginTime" to beginTime.toString(),
                "duration" to durationSeconds.toString(),
                "seats[0]" to seatId,
                "is_recommend" to "0",
                "api_time" to apiTime.toString(),
                "seatBookers[0]" to session.userId,
            )
        val response =
            httpClient.postForm(
                url = requestUrl,
                formFields = formFields,
                headers =
                    buildAuthHeaders(requestUrl, session.cookieHeader) +
                        ("Api-Token" to buildApiToken(beginTime, durationSeconds, seatId, session.userId, apiTime)),
            )
        val payload = json.parseToJsonElement(response.body).jsonObject
        val data = payload["DATA"]?.jsonObject
        require(payload.requiredText("CODE").equals("ok", ignoreCase = true)) {
            payload.optionalText("MESSAGE") ?: "预约接口返回失败"
        }
        val dataObject = data ?: throw IllegalArgumentException("预约失败，接口未返回 DATA。")
        require(dataObject.requiredText("result") == "success") {
            dataObject.optionalText("msg") ?: "预约失败，接口未返回成功结果"
        }
        val bookingId =
            dataObject.optionalText("bookingId")
                ?: dataObject.optionalText("id")
                ?: throw IllegalArgumentException("预约失败，学校接口没有返回有效 booking id。")
        return ReservationReceipt(
            bookingId = bookingId,
            message = "已提交 $roomName $seatNumber 号座位预约",
        )
    }

    private fun buildAuthHeaders(
        requestUrl: String,
        cookieHeader: String,
    ): Map<String, String> {
        val parsed = URI(requestUrl)
        val origin = URI(parsed.scheme, parsed.rawAuthority, null, null, null).toString()
        return mapOf(
            "Accept" to "application/json, text/plain, */*",
            "User-Agent" to USER_AGENT,
            "Cookie" to cookieHeader,
            "Origin" to origin,
            "Referer" to "$origin/",
        )
    }

    private fun buildApiToken(
        beginTime: Int,
        durationSeconds: Int,
        seatId: String,
        userId: String,
        apiTime: Int,
    ): String {
        val signSource =
            buildString {
                append("post&${SchoolSeatApi.BOOK_SEATS_PATH}?${SchoolSeatApi.LAB_JSON_QUERY}")
                append("&api_time").append(apiTime)
                append("&beginTime").append(beginTime)
                append("&duration").append(durationSeconds)
                append("&is_recommend0")
                append("&seatBookers[0]").append(userId.toInt())
                append("&seats[0]").append(seatId)
            }
        val digest =
            MessageDigest.getInstance("MD5")
                .digest(signSource.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        return Base64.getEncoder().encodeToString(digest.toByteArray(Charsets.UTF_8))
    }

    private fun JsonObject.requiredText(key: String): String =
        optionalText(key) ?: throw IllegalArgumentException("预约接口缺少字段：$key")

    private fun JsonObject.optionalText(key: String): String? =
        (get(key) as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotBlank)

    private companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
    }
}
