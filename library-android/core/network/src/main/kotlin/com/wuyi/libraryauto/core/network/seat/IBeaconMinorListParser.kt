package com.wuyi.libraryauto.core.network.seat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object IBeaconMinorListParser {
    fun parse(rawList: JsonArray?): List<Int> =
        rawList
            .orEmpty()
            .asSequence()
            .mapNotNull(::minorFrom)
            .filter { minor -> minor in MIN_MINOR..MAX_MINOR }
            .distinct()
            .sorted()
            .take(MAX_MINOR_COUNT)
            .toList()

    private fun minorFrom(element: JsonElement): Int? =
        when (element) {
            is JsonObject -> (element["minor"] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()
            is JsonPrimitive -> element.content.trim().toIntOrNull()
            else -> null
        }

    private const val MIN_MINOR = 0
    private const val MAX_MINOR = 65_535
    private const val MAX_MINOR_COUNT = 256
}
