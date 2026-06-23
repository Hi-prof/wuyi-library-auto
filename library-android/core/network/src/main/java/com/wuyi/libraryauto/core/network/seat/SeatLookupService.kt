package com.wuyi.libraryauto.core.network.seat

import com.wuyi.libraryauto.core.network.auth.SearchUrlResolver
import com.wuyi.libraryauto.core.network.auth.SessionBundle
import com.wuyi.libraryauto.core.network.http.OkHttpSchoolHttpClient
import com.wuyi.libraryauto.core.network.http.SchoolHttpClient
import java.net.URI

interface SeatQueryGateway {
    fun resolveSearchApiUrl(
        entryUrl: String,
        session: SessionBundle,
    ): String

    fun resolveSearchApiUrls(
        entryUrl: String,
        session: SessionBundle,
    ): List<String> = listOf(resolveSearchApiUrl(entryUrl, session))

    fun fetchSearchPage(
        searchApiUrl: String,
        session: SessionBundle,
    ): SearchPageContext

    fun searchSeats(
        searchApiUrl: String,
        session: SessionBundle,
        formFields: List<Pair<String, String>>,
    ): SeatLookupResult
}

class SeatLookupService(
    private val httpClient: SchoolHttpClient = OkHttpSchoolHttpClient(),
) : SeatQueryGateway {

    override fun resolveSearchApiUrl(
        entryUrl: String,
        session: SessionBundle,
    ): String = resolveSearchApiUrls(entryUrl, session).firstOrNull()
        ?: throw IllegalArgumentException("未能从入口页解析 searchSeats 接口地址，请检查 seat_urls 配置")

    override fun resolveSearchApiUrls(
        entryUrl: String,
        session: SessionBundle,
    ): List<String> {
        SearchUrlResolver.extractSearchApiUrl(entryUrl)?.let { directSearchUrl ->
            return listOf(directSearchUrl)
        }

        val entryApiUrl = SearchUrlResolver.extractEntryApiUrl(entryUrl)
        val response =
            httpClient.get(
                url = SchoolSeatApi.appendLabJson(entryApiUrl),
                headers = buildAuthHeaders(entryApiUrl, session.cookieHeader),
            )
        return SearchUrlResolver.extractSearchApiUrlsFromPayload(response.body, entryApiUrl)
            .ifEmpty {
                throw IllegalArgumentException("未能从入口页解析 searchSeats 接口地址，请检查 seat_urls 配置")
            }
    }

    override fun fetchSearchPage(
        searchApiUrl: String,
        session: SessionBundle,
    ): SearchPageContext {
        val response =
            httpClient.get(
                url = SchoolSeatApi.appendLabJson(searchApiUrl),
                headers = buildAuthHeaders(searchApiUrl, session.cookieHeader),
            )
        return SeatLookupRepository.parseSearchPage(
            payloadJson = response.body,
            searchApiUrl = searchApiUrl,
        )
    }

    override fun searchSeats(
        searchApiUrl: String,
        session: SessionBundle,
        formFields: List<Pair<String, String>>,
    ): SeatLookupResult {
        val response =
            httpClient.postForm(
                url = SchoolSeatApi.appendLabJson(searchApiUrl),
                formFields = formFields,
                headers = buildAuthHeaders(searchApiUrl, session.cookieHeader),
            )
        val roomMaps = SeatLookupRepository.serializeRoomMaps(response.body)
        return SeatLookupResult(
            rawPayload = response.body,
            roomMaps = roomMaps,
            selectedRoom = SeatLookupRepository.serializeSeatMap(response.body),
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

    private companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
    }
}
