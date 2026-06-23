package com.wuyi.libraryauto.core.network.seat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal object SeatMapParser {

    private val json = Json { ignoreUnknownKeys = true }
    private val selectableStates = setOf("0", "2")

    fun serializeRoomMaps(payloadJson: String): List<SeatMapSnapshot> {
        val payload = json.parseToJsonElement(payloadJson)
        val roomItems = collectUniqueRoomSeatItems(payload)
        if (roomItems.isEmpty()) {
            throw IllegalArgumentException(buildMissingSeatDataMessage(payload))
        }
        val recommendedLookup = buildRecommendedRoomLookup(payload)
        return roomItems.map { roomItem ->
            serializeRoomSeatItem(
                seatItem = roomItem,
                recommendedItem = recommendedLookup[roomItemKey(roomItem)],
            )
        }
    }

    fun serializeSeatMap(
        payloadJson: String,
        roomId: String? = null,
    ): SeatMapSnapshot {
        val payload = json.parseToJsonElement(payloadJson)
        val roomMaps = serializeRoomMaps(payloadJson)
        if (roomId != null) {
            return roomMaps.firstOrNull { it.roomId == roomId }
                ?: throw IllegalArgumentException("未找到房间 $roomId 的座位数据")
        }

        val recommendedItem = findRecommendSeatItem(payload)
        val recommendedKey = roomItemKey(recommendedItem)
        return roomMaps.firstOrNull { room ->
            room.roomId == recommendedKey || room.roomName == recommendedKey
        } ?: roomMaps.first()
    }

    private fun serializeRoomSeatItem(
        seatItem: JsonObject,
        recommendedItem: JsonObject?,
    ): SeatMapSnapshot {
        val seatMap = seatItem.requiredObject("seatMap")
        val info = seatMap.requiredObject("info")
        val seats =
            seatMap.requiredArray("POIs").map { poi ->
                serializePoi(poi as? JsonObject ?: throw IllegalArgumentException("缺少座位字段: POIs"))
            }
        val selectedSeat = seats.firstOrNull { it.state == "2" }
        val recommendedPoi = extractRecommendedPoi(recommendedItem ?: seatItem)
        return SeatMapSnapshot(
            roomId = readRoomId(seatItem),
            roomName = seatItem.scalar("roomName"),
            storey = info.scalar("storey"),
            planUrl = info.scalar("plan"),
            width = info.requiredInt("width"),
            height = info.requiredInt("height"),
            availableCount = seats.count { it.selectable },
            lockedCount = seats.count { !it.selectable },
            selectedSeatId = selectedSeat?.seatId,
            selectedSeatNumber = selectedSeat?.seatNumber,
            systemRecommendedSeatId = recommendedPoi?.requiredString("id"),
            systemRecommendedSeatNumber = recommendedPoi?.scalar("title")?.takeIf(String::isNotBlank),
            seats = seats,
        )
    }

    private fun serializePoi(poi: JsonObject): SeatPoi {
        val state = poi.normalizeState("state")
        return SeatPoi(
            seatId = poi.requiredString("id"),
            seatNumber = poi.scalar("title"),
            x = poi.requiredInt("x"),
            y = poi.requiredInt("y"),
            w = poi.requiredInt("w"),
            h = poi.requiredInt("h"),
            state = state,
            selectable = state in selectableStates,
            recommended = poi.booleanLike("recommend"),
            hasSocket = poi.normalizeState("have_socket") == "1",
        )
    }

    private fun findRecommendSeatItem(payload: JsonElement): JsonObject =
        collectRecommendSeatItems(payload).firstOrNull(::seatItemHasSelectedPoi)
            ?: collectRecommendSeatItems(payload).firstOrNull { item -> item.booleanLike("ifRecommend") }
            ?: collectRecommendSeatItems(payload).firstOrNull()
            ?: throw IllegalArgumentException(buildMissingSeatDataMessage(payload))

    private fun collectUniqueRoomSeatItems(payload: JsonElement): List<JsonObject> {
        val candidates =
            buildList {
                addAll(collectRecommendSeatItems((payload as? JsonObject)?.get("allContent")))
                addAll(collectRecommendSeatItems((payload as? JsonObject)?.get("content")))
            }
        val uniqueItems = mutableListOf<JsonObject>()
        val indexByKey = mutableMapOf<String, Int>()
        candidates.forEach { seatItem ->
            val key = roomItemKey(seatItem)
            val existingIndex = indexByKey[key]
            if (existingIndex == null) {
                indexByKey[key] = uniqueItems.size
                uniqueItems += seatItem
            } else if (shouldReplaceRoomSeatItem(uniqueItems[existingIndex], seatItem)) {
                uniqueItems[existingIndex] = seatItem
            }
        }
        return uniqueItems
    }

    private fun buildRecommendedRoomLookup(payload: JsonElement): Map<String, JsonObject> {
        val candidates =
            buildList {
                addAll(collectRecommendSeatItems((payload as? JsonObject)?.get("content")))
                addAll(collectRecommendSeatItems((payload as? JsonObject)?.get("allContent")))
            }
        val lookup = linkedMapOf<String, JsonObject>()
        candidates.forEach { seatItem ->
            if (!seatItemHasSelectedPoi(seatItem) && !seatItem.booleanLike("ifRecommend")) {
                return@forEach
            }
            val key = roomItemKey(seatItem)
            val current = lookup[key]
            if (current == null || seatItemHasSelectedPoi(seatItem)) {
                lookup[key] = seatItem
            }
        }
        return lookup
    }

    private fun collectRecommendSeatItems(node: JsonElement?): List<JsonObject> {
        if (node == null) {
            return emptyList()
        }
        return when (node) {
            is JsonObject -> {
                val current =
                    if (node.scalar("ui_type") == "ht.Seat.RecommendSeatItem" && node["seatMap"] is JsonObject) {
                        listOf(node)
                    } else {
                        emptyList()
                    }
                current + node.values.flatMap(::collectRecommendSeatItems)
            }

            is JsonArray -> node.flatMap(::collectRecommendSeatItems)
            else -> emptyList()
        }
    }

    private fun seatItemHasSelectedPoi(seatItem: JsonObject): Boolean =
        seatItem
            .requiredObject("seatMap")
            .requiredArray("POIs")
            .any { poi -> (poi as? JsonObject)?.normalizeState("state") == "2" }

    private fun roomItemKey(seatItem: JsonObject): String =
        readRoomId(seatItem).ifBlank { seatItem.scalar("roomName").ifBlank { seatItem.hashCode().toString() } }

    private fun readRoomId(seatItem: JsonObject): String =
        seatItem.requiredObject("seatMap").requiredObject("info").scalar("id")

    private fun shouldReplaceRoomSeatItem(
        currentItem: JsonObject,
        candidateItem: JsonObject,
    ): Boolean {
        val currentRecommended = currentItem.booleanLike("ifRecommend")
        val candidateRecommended = candidateItem.booleanLike("ifRecommend")
        if (currentRecommended != candidateRecommended) {
            return currentRecommended && !candidateRecommended
        }
        return countPois(candidateItem) > countPois(currentItem)
    }

    private fun countPois(seatItem: JsonObject): Int =
        seatItem.requiredObject("seatMap").requiredArray("POIs").size

    private fun extractRecommendedPoi(seatItem: JsonObject): JsonObject? {
        val pois = seatItem.requiredObject("seatMap").requiredArray("POIs")
        return pois.firstNotNullOfOrNull { poi ->
            (poi as? JsonObject)?.takeIf { it.normalizeState("state") == "2" }
        } ?: pois.firstNotNullOfOrNull { poi ->
            (poi as? JsonObject)?.takeIf { it.booleanLike("recommend") }
        }
    }

    private fun buildMissingSeatDataMessage(payload: JsonElement): String {
        val detail = describePayload(payload)
        return if (detail == null) {
            "未找到座位数据"
        } else {
            "未找到座位数据：$detail"
        }
    }

    private fun describePayload(node: JsonElement): String? =
        when (node) {
            is JsonObject -> {
                val parts = buildList {
                    node.scalar("ui_type")
                        .takeIf(String::isNotBlank)
                        ?.let { uiType -> add(uiType) }
                    node.scalar("CODE").takeIf(String::isNotBlank)?.let { code -> add("CODE=$code") }
                    extractPayloadMessage(node)
                        ?.takeIf(String::isNotBlank)
                        ?.let { message -> add("message=$message") }
                }
                parts.distinct().joinToString("，").takeIf(String::isNotBlank)
            }

            else -> null
        }

    private fun extractPayloadMessage(node: JsonElement): String? =
        when (node) {
            is JsonObject -> {
                val candidateKeys =
                    if (node.scalar("ui_type") == "com.Message") {
                        listOf("title", "text", "content", "message", "msg", "error")
                    } else {
                        listOf("message", "msg", "error")
                    }
                candidateKeys.firstNotNullOfOrNull { key ->
                    node.scalar(key).takeIf(String::isNotBlank)
                } ?: node.values.firstNotNullOfOrNull(::extractPayloadMessage)
            }

            is JsonArray -> node.firstNotNullOfOrNull(::extractPayloadMessage)
            else -> null
        }

    private fun JsonObject.requiredObject(key: String): JsonObject =
        get(key)?.jsonObject ?: throw IllegalArgumentException("缺少座位字段: $key")

    private fun JsonObject.requiredArray(key: String): JsonArray =
        get(key)?.jsonArray ?: throw IllegalArgumentException("缺少座位字段: $key")

    private fun JsonObject.requiredInt(key: String): Int =
        (get(key) as? JsonPrimitive)?.intOrNull
            ?: throw IllegalArgumentException("座位字段 $key 不是有效整数")

    private fun JsonObject.requiredString(key: String): String =
        scalar(key).ifBlank { throw IllegalArgumentException("缺少座位字段: $key") }

    private fun JsonObject.scalar(key: String): String =
        (get(key) as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.normalizeState(key: String): String {
        val primitive = get(key) as? JsonPrimitive ?: return ""
        primitive.intOrNull?.let { return it.toString() }
        primitive.booleanOrNull?.let { return if (it) "1" else "0" }
        return primitive.contentOrNull.orEmpty().trim().ifBlank { "" }
    }

    private fun JsonObject.booleanLike(key: String): Boolean {
        val value = get(key) ?: return false
        return when (value) {
            is JsonPrimitive -> {
                value.booleanOrNull?.let { return it }
                value.intOrNull?.let { return it != 0 }
                val text = value.contentOrNull.orEmpty().trim().lowercase()
                when {
                    text.isBlank() || text == "0" || text == "false" || text == "null" -> false
                    text == "1" || text == "true" -> true
                    text.toDoubleOrNull() != null -> text.toDouble() != 0.0
                    else -> true
                }
            }

            is JsonArray -> value.isNotEmpty()
            is JsonObject -> value.isNotEmpty()
        }
    }
}
