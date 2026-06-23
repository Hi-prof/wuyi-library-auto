package com.wuyi.libraryauto.core.network.seat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

object SeatLookupRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val selectableStates = setOf("0", "2")
    private val falseStrings = setOf("", "0", "false", "null")
    private val trueStrings = setOf("1", "true")

    fun parseSearchPage(
        payloadJson: String,
        searchApiUrl: String = "",
    ): SearchPageContext {
        val payload = json.parseToJsonElement(payloadJson).jsonObject
        val data = payload.requiredObject("data")
        val default = data.requiredObject("default")
        val spaceCategory = data.requiredObject("space_category")
        return SearchPageContext(
            searchApiUrl = searchApiUrl,
            rawPayload = payloadJson,
            defaultBeginTime = default.requiredInt("date"),
            defaultDurationHours = default.requiredInt("duration"),
            defaultPeopleCount = default.requiredInt("num"),
            categoryId = spaceCategory.requiredScalar("category_id"),
            contentId = spaceCategory.requiredScalar("content_id"),
        )
    }

    fun buildSearchFormPayload(payloadJson: String): List<Pair<String, String>> =
        buildSearchFormPayload(parseSearchPage(payloadJson))

    fun buildSearchFormPayload(context: SearchPageContext): List<Pair<String, String>> =
        listOf(
            "beginTime" to context.defaultBeginTime.toString(),
            "duration" to (context.defaultDurationHours * 3600).toString(),
            "num" to context.defaultPeopleCount.toString(),
            "space_category[category_id]" to context.categoryId,
            "space_category[content_id]" to context.contentId,
        )

    fun buildCustomSearchFormPayload(
        payloadJson: String,
        beginTime: Int,
        durationSeconds: Int,
        peopleCount: Int,
    ): List<Pair<String, String>> =
        buildCustomSearchFormPayload(
            context = parseSearchPage(payloadJson),
            beginTime = beginTime,
            durationSeconds = durationSeconds,
            peopleCount = peopleCount,
        )

    fun buildCustomSearchFormPayload(
        context: SearchPageContext,
        beginTime: Int,
        durationSeconds: Int,
        peopleCount: Int,
    ): List<Pair<String, String>> =
        listOf(
            "beginTime" to beginTime.toString(),
            "duration" to durationSeconds.toString(),
            "num" to peopleCount.toString(),
            "space_category[category_id]" to context.categoryId,
            "space_category[content_id]" to context.contentId,
        )

    fun serializeSeatMap(
        payloadJson: String,
        roomId: String? = null,
    ): SeatMapSnapshot = SeatMapParser.serializeSeatMap(payloadJson, roomId)

    fun serializeRoomMaps(payloadJson: String): List<SeatMapSnapshot> =
        SeatMapParser.serializeRoomMaps(payloadJson)

    fun serializePoi(poi: Map<String, Any?>): SeatPoi {
        val state = poi.requiredState("state")
        return SeatPoi(
            seatId = poi.requiredValue("id").toString(),
            seatNumber = poi["title"].orEmptyString().trim(),
            x = poi.requiredInt("x"),
            y = poi.requiredInt("y"),
            w = poi.requiredInt("w"),
            h = poi.requiredInt("h"),
            state = state,
            selectable = state in selectableStates,
            recommended = poi["recommend"].toPythonBoolLike(),
            hasSocket = poi["have_socket"]?.normalizeState() == "1",
        )
    }

    private fun Map<String, Any?>.requiredValue(key: String): Any =
        get(key) ?: throw IllegalArgumentException("缺少座位字段: $key")

    private fun Map<String, Any?>.requiredInt(key: String): Int =
        when (val value = requiredValue(key)) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        } ?: throw IllegalArgumentException("座位字段 $key 不是有效整数")

    private fun Map<String, Any?>.requiredState(key: String): String =
        requiredValue(key).normalizeState()

    private fun JsonObject.requiredObject(key: String): JsonObject =
        get(key)?.jsonObject ?: throw IllegalArgumentException(buildMissingQueryFieldMessage(this, key))

    private fun JsonObject.requiredInt(key: String): Int =
        requiredScalar(key).toIntOrNull() ?: throw IllegalArgumentException("查询页字段 $key 不是有效整数")

    private fun JsonObject.requiredScalar(key: String): String =
        (get(key) as? JsonPrimitive)?.content?.trim()
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("缺少查询页字段: $key")

    private fun buildMissingQueryFieldMessage(
        payload: JsonObject,
        key: String,
    ): String {
        val detail = payload.describePayload()
        return if (detail == null) {
            "缺少查询页字段: $key"
        } else {
            "查询页没有返回 $key，当前接口返回：$detail"
        }
    }

    private fun JsonObject.describePayload(): String? {
        val parts = buildList {
            scalarValue(this@describePayload, "ui_type")
                .takeIf(String::isNotBlank)
                ?.let { uiType -> add(uiType) }
            scalarValue(this@describePayload, "CODE")
                .takeIf(String::isNotBlank)
                ?.let { code -> add("CODE=$code") }
            extractPayloadMessage(this@describePayload)
                ?.takeIf(String::isNotBlank)
                ?.let { message -> add("message=$message") }
        }
        return parts.distinct().joinToString("，").takeIf(String::isNotBlank)
    }

    private fun extractPayloadMessage(node: JsonElement): String? =
        when (node) {
            is JsonObject -> {
                val candidateKeys =
                    if (scalarValue(node, "ui_type") == "com.Message") {
                        listOf("title", "text", "content", "message", "msg", "error")
                    } else {
                        listOf("message", "msg", "error")
                    }
                candidateKeys.firstNotNullOfOrNull { key ->
                    scalarValue(node, key).takeIf(String::isNotBlank)
                } ?: node.values.firstNotNullOfOrNull(::extractPayloadMessage)
            }

            is JsonArray -> node.firstNotNullOfOrNull(::extractPayloadMessage)
            else -> null
        }

    private fun scalarValue(
        node: JsonObject,
        key: String,
    ): String = (node[key] as? JsonPrimitive)?.content?.trim().orEmpty()

    private fun Any.normalizeState(): String =
        when (this) {
            is Number -> normalizeNumberString(toDouble())
            is String -> {
                val text = trim()
                text.toDoubleOrNull()?.let(::normalizeNumberString) ?: text
            }
            else -> toString()
        }

    private fun Any?.orEmptyString(): String = this?.toString().orEmpty()

    private fun Any?.toPythonBoolLike(): Boolean =
        when (this) {
            null -> false
            is Boolean -> this
            is Number -> toDouble() != 0.0
            is String -> {
                val normalized = trim().lowercase()
                when {
                    normalized in falseStrings -> false
                    normalized in trueStrings -> true
                    normalized.toDoubleOrNull() != null -> normalized.toDouble() != 0.0
                    else -> true
                }
            }
            is Collection<*> -> isNotEmpty()
            is Map<*, *> -> isNotEmpty()
            else -> true
        }

    private fun normalizeNumberString(value: Double): String {
        val longValue = value.toLong()
        return if (value == longValue.toDouble()) {
            longValue.toString()
        } else {
            value.toString()
        }
    }
}
