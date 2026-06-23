package com.wuyi.libraryauto.core.network.seat

import com.wuyi.libraryauto.core.domain.model.CheckInWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class SeatBookingLiveState {
    NEED_LOGIN,
    IDLE,
    RESERVED_WAITING_SIGNIN,
    ACTIVE_SIGNED_IN,
    FINISHED_OR_HISTORY,
}

data class SeatBookingSnapshot(
    val liveState: SeatBookingLiveState,
    val bookingId: String? = null,
    val roomName: String = "",
    val seatNumber: String = "",
    val beginLabel: String = "",
    val statusLabel: String = "",
    val checkinWindowOpen: Boolean = false,
)

open class SeatBookingStatusService(
    private val api: SchoolSeatApi,
) {
    private val json = Json { ignoreUnknownKeys = true }

    open fun loadCurrentBooking(url: String): SeatBookingSnapshot {
        val payload = parseBookingPayload(url)
        if (payload.isNeedLogin) {
            return SeatBookingSnapshot(
                liveState = SeatBookingLiveState.NEED_LOGIN,
                statusLabel = payload.needLoginMessage,
            )
        }
        val bookingList = payload.bookingList
        val currentBooking =
            bookingList
                .filter { item -> item.optionalText("status") in trackedStatuses }
                .minWithOrNull(
                    compareBy<JsonObject>({ bookingPriority(it) }, { -bookingTimestamp(it) }),
                )
        if (currentBooking == null) {
            return if (bookingList.isEmpty()) {
                SeatBookingSnapshot(
                    liveState = SeatBookingLiveState.IDLE,
                    statusLabel = "暂无预约",
                )
            } else {
                SeatBookingSnapshot(
                    liveState = SeatBookingLiveState.FINISHED_OR_HISTORY,
                    statusLabel = "最近记录已结束",
                )
            }
        }

        val status = currentBooking.optionalText("status").orEmpty()
        val liveState =
            if (status in signedInStatuses) {
                SeatBookingLiveState.ACTIVE_SIGNED_IN
            } else {
                SeatBookingLiveState.RESERVED_WAITING_SIGNIN
            }
        return currentBooking.toSnapshot(liveState)
    }

    /**
     * 一次拉取该账号的全部「活跃预约」（包含待签到 / 已签到，去除历史和已取消）。
     *
     * 与 [loadCurrentBooking] 不同：不做最近一条的择优选择，而是按学校接口返回的顺序，
     * 把每条预约转成 [SeatBookingSnapshot]，便于 UI 同时展示多条预约并对每条单独操作。
     *
     * - NEED_LOGIN 时返回单元素列表：携带 [SeatBookingLiveState.NEED_LOGIN]
     *   与提示文案，调用方据此触发会话刷新。
     * - 没有任何活跃预约时返回空列表，调用方可继续当作 IDLE 处理。
     */
    open fun loadActiveBookings(url: String): List<SeatBookingSnapshot> {
        val payload = parseBookingPayload(url)
        if (payload.isNeedLogin) {
            return listOf(
                SeatBookingSnapshot(
                    liveState = SeatBookingLiveState.NEED_LOGIN,
                    statusLabel = payload.needLoginMessage,
                ),
            )
        }
        return payload.bookingList
            .filter { item -> item.optionalText("status") in trackedStatuses }
            .sortedWith(
                compareBy<JsonObject>({ bookingPriority(it) }, { -bookingTimestamp(it) }),
            )
            .map { booking ->
                val status = booking.optionalText("status").orEmpty()
                val liveState =
                    if (status in signedInStatuses) {
                        SeatBookingLiveState.ACTIVE_SIGNED_IN
                    } else {
                        SeatBookingLiveState.RESERVED_WAITING_SIGNIN
                    }
                booking.toSnapshot(liveState)
            }
    }

    open fun loadBooking(
        url: String,
        bookingId: String,
    ): SeatBookingSnapshot? {
        val payload = parseBookingPayload(url)
        if (payload.isNeedLogin) {
            return null
        }
        val booking =
            payload.bookingList.firstOrNull { item ->
                item.optionalText("id") == bookingId.trim()
            } ?: return null
        val status = booking.optionalText("status").orEmpty()
        val liveState =
            if (status in signedInStatuses) {
                SeatBookingLiveState.ACTIVE_SIGNED_IN
            } else {
                SeatBookingLiveState.RESERVED_WAITING_SIGNIN
        }
        return booking.toSnapshot(liveState)
    }

    open fun loadBookingDetail(
        url: String,
        bookingId: String,
    ): BookingDetail? {
        val payload = parseBookingPayload(url)
        if (payload.isNeedLogin) {
            return null
        }
        val normalizedBookingId = bookingId.trim()
        val booking =
            payload.bookingList.firstOrNull { item ->
                item.optionalText("id") == normalizedBookingId
            } ?: return null
        return booking.toBookingDetail(normalizedBookingId)
    }

    /**
     * 一次请求拿到当前最相关预约的完整详情：与 [loadCurrentBooking] 共用同一选择器，
     * 但返回 [BookingDetail]（包含 window、ibeacons 和签到状态），便于无本地 task 时
     * 直接驱动周期签到。
     */
    /**
     * 一次请求拿到该账号当前的全部「活跃预约」完整详情：与 [loadActiveBookings] 共用筛选规则，
     * 但每条预约都返回 [BookingDetail]（含 window、ibeacons 与签到状态），便于上层一次同步全部
     * 预约到本地 reservation_tasks 并按需各自调度 GuardWorker，避免 N+1 次接口往返。
     */
    open fun loadActiveBookingDetails(url: String): List<BookingDetail> {
        val payload = parseBookingPayload(url)
        if (payload.isNeedLogin) {
            return emptyList()
        }
        return payload.bookingList
            .filter { item -> item.optionalText("status") in trackedStatuses }
            .sortedWith(
                compareBy<JsonObject>({ bookingPriority(it) }, { -bookingTimestamp(it) }),
            )
            .mapNotNull { booking ->
                val bookingId = booking.optionalText("id")?.trim().orEmpty()
                if (bookingId.isBlank()) {
                    null
                } else {
                    booking.toBookingDetail(bookingId)
                }
            }
    }

    /**
     * 一次请求拿到当前最相关预约的完整详情：与 [loadCurrentBooking] 共用同一选择器，
     * 但返回 [BookingDetail]（包含 window、ibeacons 和签到状态），便于无本地 task 时
     * 直接驱动周期签到。
     */
    open fun loadCurrentBookingDetail(url: String): BookingDetail? {
        val payload = parseBookingPayload(url)
        if (payload.isNeedLogin) {
            return null
        }
        val currentBooking =
            payload.bookingList
                .filter { item -> item.optionalText("status") in trackedStatuses }
                .minWithOrNull(
                    compareBy<JsonObject>({ bookingPriority(it) }, { -bookingTimestamp(it) }),
                ) ?: return null
        val bookingId = currentBooking.optionalText("id")?.trim().orEmpty()
        if (bookingId.isBlank()) {
            return null
        }
        return currentBooking.toBookingDetail(bookingId)
    }

    open fun loadActiveBookingDates(url: String): Set<String> {
        val payload = parseBookingPayload(url)
        if (payload.isNeedLogin) {
            return emptySet()
        }
        return payload.bookingList
            .asSequence()
            .filter { item -> item.optionalText("status") in trackedStatuses }
            .mapNotNull { item -> item.beginLabel().takeIf(String::isNotBlank)?.substring(0, 10) }
            .toSet()
    }

    private fun JsonObject.toSnapshot(liveState: SeatBookingLiveState): SeatBookingSnapshot =
        SeatBookingSnapshot(
            liveState = liveState,
            bookingId = optionalText("id"),
            roomName = optionalText("room", "roomName").orEmpty(),
            seatNumber = optionalText("no", "seatNum").orEmpty(),
            beginLabel = beginLabel(),
            statusLabel = statusLabel(liveState),
            checkinWindowOpen = isCheckinWindowOpen(optionalText("status").orEmpty()),
        )

    private fun JsonObject.toBookingDetail(bookingId: String): BookingDetail {
        val status = optionalText("status").orEmpty()
        val liveState =
            if (status in signedInStatuses) {
                SeatBookingLiveState.ACTIVE_SIGNED_IN
            } else {
                SeatBookingLiveState.RESERVED_WAITING_SIGNIN
            }
        return BookingDetail(
            bookingId = optionalText("id") ?: bookingId,
            window = CheckInWindow.fromRemote(
                startEpochSeconds = optionalLong("time") ?: 0L,
                limitSignAgoSeconds = optionalText("limitSignAgo"),
                limitSignBackSeconds = optionalText("limitSignBack"),
            ),
            expectedMinors = IBeaconMinorListParser.parse(get("ibeacons") as? JsonArray),
            statusLabel = statusLabel(liveState),
            isAlreadySignedIn = status in signedInStatuses,
        )
    }

    private fun parseBookingPayload(url: String): ParsedBookingPayload {
        val responseBody = api.fetchBookingList(url)
        if (responseBody.isBlank()) {
            throw IllegalStateException("预约状态接口返回空响应，请稍后重试。")
        }
        if (responseBody.looksLikeHtmlLoginPage()) {
            return ParsedBookingPayload(
                bookingList = emptyList(),
                isNeedLogin = true,
                needLoginMessage = "当前登录态可能已失效，请点击“刷新认证”。",
            )
        }
        val payload =
            runCatching { json.parseToJsonElement(responseBody).jsonObject }
                .getOrElse { error ->
                    throw IllegalStateException(buildInvalidPayloadMessage(responseBody), error)
                }
        val code = payload.optionalText("CODE")
        if (code != null && !code.equals("ok", ignoreCase = true)) {
            return ParsedBookingPayload(
                bookingList = emptyList(),
                isNeedLogin = true,
                needLoginMessage = payload.optionalText("MESSAGE") ?: "需登录",
            )
        }
        return ParsedBookingPayload(
            bookingList = payload.bookingItems(),
            isNeedLogin = false,
            needLoginMessage = "",
        )
    }

    private fun JsonObject.bookingItems(): List<JsonObject> =
        legacyBookingItems().ifEmpty { currentBookingItems() }

    private fun String.looksLikeHtmlLoginPage(): Boolean {
        val trimmed = trim()
        if (!trimmed.startsWith("<")) {
            return false
        }
        val lowerCase = trimmed.lowercase()
        return lowerCase.contains("<html") ||
            lowerCase.contains("<!doctype html") ||
            lowerCase.contains("/user/index/login") ||
            lowerCase.contains("请先登录")
    }

    private fun buildInvalidPayloadMessage(responseBody: String): String {
        val preview =
            responseBody
                .trim()
                .replace(Regex("\\s+"), " ")
                .take(80)
        return "预约状态接口返回了非 JSON 内容，请刷新认证后重试。响应片段：$preview"
    }

    private fun JsonObject.legacyBookingItems(): List<JsonObject> =
        (((get("DATA") as? JsonObject)?.get("list")) as? JsonArray)
            ?.mapNotNull { element -> element as? JsonObject }
            .orEmpty()

    private fun JsonObject.currentBookingItems(): List<JsonObject> =
        (((get("content") as? JsonObject)?.get("defaultItems")) as? JsonArray)
            ?.mapNotNull { element -> element as? JsonObject }
            .orEmpty()

    private fun JsonObject.optionalText(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            (get(key) as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotBlank)
        }

    private fun JsonObject.optionalLong(key: String): Long? =
        (get(key) as? JsonPrimitive)?.content?.trim()?.toLongOrNull()

    private fun JsonObject.beginLabel(): String {
        val legacyLabel =
            listOf(
                optionalText("day").orEmpty(),
                optionalText("begin").orEmpty(),
            ).filter(String::isNotBlank).joinToString(" ")
        if (legacyLabel.isNotBlank()) {
            return legacyLabel
        }
        val timestamp = optionalLong("time") ?: return ""
        return bookingTimeFormatter.format(Instant.ofEpochSecond(timestamp).atZone(shanghaiZone))
    }

    private fun JsonObject.statusLabel(liveState: SeatBookingLiveState): String {
        val status = optionalText("status").orEmpty()
        currentStatusLabels[status]?.let { return it }
        return if (liveState == SeatBookingLiveState.ACTIVE_SIGNED_IN) "已签到" else "待签到"
    }

    private fun JsonObject.isCheckinWindowOpen(status: String): Boolean {
        optionalText("sign_can")?.let { return it == "1" }
        if (status != reserveStatus) {
            return false
        }
        val startTime = optionalLong("time") ?: return false
        val nowTime = optionalLong("nowTime") ?: return false
        val limitSignAgo = optionalLong("limitSignAgo") ?: 0L
        val limitSignBack = optionalLong("limitSignBack") ?: 0L
        val windowStart = startTime - limitSignAgo
        val windowEnd = startTime + limitSignBack
        return nowTime in windowStart until windowEnd
    }

    private fun bookingPriority(item: JsonObject): Int {
        val status = item.optionalText("status").orEmpty()
        return when {
            status in signedInStatuses -> 0
            item.isCheckinWindowOpen(status) -> 1
            status in reservableStatuses -> 2
            else -> 3
        }
    }

    private fun bookingTimestamp(item: JsonObject): Long = item.optionalLong("time") ?: 0L

    private data class ParsedBookingPayload(
        val bookingList: List<JsonObject>,
        val isNeedLogin: Boolean,
        val needLoginMessage: String,
    )

    private companion object {
        private const val checkedInStatus = "CheckIn"
        private const val reserveStatus = "0"
        private val reservableStatuses = setOf("Reserve", reserveStatus, "8")
        private val signedInStatuses = setOf(checkedInStatus, "1", "2")
        private val trackedStatuses = reservableStatuses + signedInStatuses
        private val currentStatusLabels =
            mapOf(
                reserveStatus to "待签到",
                "1" to "签到成功，使用中",
                "2" to "暂离中",
                "3" to "已签退结束",
                "4" to "已取消",
                "5" to "未签到结束",
                "6" to "暂离未归结束",
                "7" to "系统签退结束",
                "8" to "预约待确认",
                "9" to "拒绝预约",
            )
        private val shanghaiZone = ZoneId.of("Asia/Shanghai")
        private val bookingTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
