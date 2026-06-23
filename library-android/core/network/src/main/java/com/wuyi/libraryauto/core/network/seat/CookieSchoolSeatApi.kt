package com.wuyi.libraryauto.core.network.seat

import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.core.network.http.OkHttpSchoolHttpClient
import com.wuyi.libraryauto.core.network.http.SchoolHttpClient
import java.net.URI

class CookieSchoolSeatApi(
    private val session: AuthenticatedSession,
    private val httpClient: SchoolHttpClient = OkHttpSchoolHttpClient(),
) : SchoolSeatApi {
    override fun fetchSearchPage(url: String): String = httpClient.get(url, buildAuthHeaders(url)).body

    override fun searchSeats(
        url: String,
        requestBody: String,
    ): String = error("CookieSchoolSeatApi does not support raw requestBody searchSeats")

    override fun reserveSeats(
        url: String,
        requestBody: String,
    ): String = error("CookieSchoolSeatApi does not support raw requestBody reserveSeats")

    override fun fetchBookingList(url: String): String = httpClient.get(url, buildAuthHeaders(url)).body

    override fun checkIn(url: String): String = postAction(url)

    override fun checkout(url: String): String = postAction(url)

    override fun cancelBooking(url: String): String = postAction(url)

    private fun buildAuthHeaders(requestUrl: String): Map<String, String> {
        val parsed = URI(requestUrl)
        val origin = URI(parsed.scheme, parsed.rawAuthority, null, null, null).toString()
        return mapOf(
            "Accept" to "application/json, text/plain, */*",
            "User-Agent" to USER_AGENT,
            "Cookie" to session.session.cookieHeader,
            "Origin" to origin,
            "Referer" to "$origin/",
        )
    }

    private fun postAction(url: String): String = httpClient.postForm(url, emptyList(), buildAuthHeaders(url)).body

    private companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
    }
}
