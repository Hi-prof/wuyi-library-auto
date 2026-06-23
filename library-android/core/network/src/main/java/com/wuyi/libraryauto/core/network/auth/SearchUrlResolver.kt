package com.wuyi.libraryauto.core.network.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URI

object SearchUrlResolver {

    private const val SEARCH_PATH = "/Seat/Index/searchSeats"
    private const val LAB_JSON = "LAB_JSON"
    private val json = Json { ignoreUnknownKeys = true }

    fun extractSearchApiUrl(entryUrl: String): String? {
        val parsed = URI(entryUrl)
        if (isSearchPath(parsed.path)) {
            return removeLabJson(buildUrl(parsed.scheme, parsed.rawAuthority, parsed.rawPath, parsed.rawQuery))
        }

        val fragment = parsed.rawFragment.orEmpty().removePrefix("!")
        val fragmentPath = fragment.substringBefore("?")
        if (!isSearchPath(fragmentPath)) {
            return null
        }

        val fragmentQuery = fragment.substringAfter("?", missingDelimiterValue = "")
        return removeLabJson(buildUrl(parsed.scheme, parsed.rawAuthority, fragmentPath, fragmentQuery))
    }

    fun extractEntryApiUrl(entryUrl: String): String {
        val parsed = URI(entryUrl)
        val fragment = parsed.rawFragment.orEmpty().removePrefix("!")
        if (fragment.startsWith("/")) {
            val fragmentPath = fragment.substringBefore("?")
            val fragmentQuery = fragment.substringAfter("?", missingDelimiterValue = "")
            return buildUrl(parsed.scheme, parsed.rawAuthority, fragmentPath, fragmentQuery)
        }
        return buildUrl(parsed.scheme, parsed.rawAuthority, parsed.rawPath, parsed.rawQuery)
    }

    fun extractSearchApiUrlFromPayload(
        payloadJson: String,
        requestUrl: String,
    ): String? = extractSearchApiUrlsFromPayload(payloadJson, requestUrl).firstOrNull()

    fun extractSearchApiUrlsFromPayload(
        payloadJson: String,
        requestUrl: String,
    ): List<String> = extractSearchApiUrlsFromPayload(json.parseToJsonElement(payloadJson), requestUrl)

    private fun extractSearchApiUrlsFromPayload(
        payload: JsonElement,
        requestUrl: String,
    ): List<String> {
        val results = linkedSetOf<String>()
        collectSearchApiUrls(payload, requestUrl, results)
        return results.toList()
    }

    private fun collectSearchApiUrls(
        payload: JsonElement,
        requestUrl: String,
        results: MutableSet<String>,
    ) {
        when (payload) {
            is JsonObject -> {
                val linkedUrl =
                    (payload["link"] as? JsonObject)
                        ?.get("url")
                        ?.let { it as? JsonPrimitive }
                        ?.content
                        ?.trim()
                        .orEmpty()
                if (linkedUrl.isNotBlank()) {
                    extractSearchApiUrl(URI(requestUrl).resolve(linkedUrl).toString())
                        ?.let(results::add)
                }
                payload.values.forEach { value ->
                    collectSearchApiUrls(value, requestUrl, results)
                }
            }

            else -> payloadAsArray(payload).forEach { value ->
                collectSearchApiUrls(value, requestUrl, results)
            }
        }
    }

    private fun payloadAsArray(payload: JsonElement): Iterable<JsonElement> =
        runCatching { payload.jsonObject.values }.getOrNull() ?: payload.jsonArrayOrEmpty()

    private fun JsonElement.jsonArrayOrEmpty(): List<JsonElement> =
        runCatching { jsonArray.toList() }.getOrElse { emptyList() }

    private fun isSearchPath(path: String?): Boolean = path == SEARCH_PATH

    private fun buildUrl(
        scheme: String?,
        authority: String?,
        path: String?,
        query: String?,
    ): String =
        buildString {
            if (!scheme.isNullOrEmpty()) {
                append(scheme)
                append("://")
            }
            if (!authority.isNullOrEmpty()) {
                append(authority)
            }
            append(path.orEmpty())
            query?.takeIf(String::isNotEmpty)?.let {
                append("?")
                append(it)
            }
        }

    private fun removeLabJson(url: String): String {
        val parsed = URI(url)
        val cleanQuery =
            parsed.rawQuery
                ?.split("&")
                ?.filter { segment -> segment.substringBefore("=") != LAB_JSON }
                ?.joinToString("&")
                ?.takeIf(String::isNotEmpty)
        return buildUrl(parsed.scheme, parsed.rawAuthority, parsed.rawPath, cleanQuery)
    }
}
