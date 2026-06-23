package com.wuyi.libraryauto.ui.repository.session

import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.core.network.auth.SchoolAuthApi
import com.wuyi.libraryauto.core.network.auth.SchoolAuthRepository
import org.json.JSONObject

class WebSessionBootstrapper(
    private val sessionRepository: SessionRepository,
) {

    fun currentSession(): AuthenticatedSession? = sessionRepository.currentSession()

    fun needsBootstrap(session: AuthenticatedSession): Boolean =
        !hasRequiredCookies(session.session.cookieHeader)

    fun bootstrapEntryUrl(session: AuthenticatedSession): String =
        session.origin + LOGIN_PAGE_HASH

    fun bootstrapTargetUrl(session: AuthenticatedSession): String =
        session.origin + SPACE_LIST_HASH

    fun buildStorageSeedScript(session: AuthenticatedSession): String =
        """
        (() => {
          localStorage.setItem(${quote(storageKey("installationId"))}, ${quote(session.installationId)});
          localStorage.setItem(${quote(storageKey("currentUser"))}, ${quote(session.currentUserJson)});
          return "seeded";
        })();
        """.trimIndent()

    fun hasRequiredCookies(cookieHeader: String): Boolean {
        val cookieNames =
            normalizeCookieHeader(cookieHeader)
                .split(';')
                .asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .map { segment -> segment.substringBefore('=').trim() }
                .toSet()
        return REQUIRED_COOKIE_NAMES.all(cookieNames::contains)
    }

    fun saveWebSession(
        session: AuthenticatedSession,
        cookieHeader: String,
    ) {
        val mergedCookieHeader = mergeCookieHeaders(session.session.cookieHeader, cookieHeader)
        sessionRepository.save(
            session.copy(
                session = session.session.copy(cookieHeader = mergedCookieHeader),
                cookies = parseCookieRecords(mergedCookieHeader),
            ),
        )
    }

    private fun mergeCookieHeaders(
        currentCookieHeader: String,
        updatedCookieHeader: String,
    ): String {
        val mergedByName = linkedMapOf<String, String>()
        parseCookiePairs(currentCookieHeader).forEach { (name, value) ->
            mergedByName[name] = value
        }
        parseCookiePairs(updatedCookieHeader).forEach { (name, value) ->
            mergedByName[name] = value
        }
        return mergedByName.entries.joinToString("; ") { (name, value) -> "$name=$value" }
    }

    private fun parseCookiePairs(cookieHeader: String): List<Pair<String, String>> =
        normalizeCookieHeader(cookieHeader)
            .split(';')
            .map(String::trim)
            .filter(String::isNotBlank)
            .mapNotNull { segment ->
                val name = segment.substringBefore('=', "").trim()
                val value = segment.substringAfter('=', "").trim()
                if (name.isBlank() || value.isBlank()) {
                    null
                } else {
                    name to value
                }
            }

    private fun parseCookieRecords(cookieHeader: String): List<SchoolAuthRepository.CookieRecord> =
        parseCookiePairs(cookieHeader)
            .map { (name, value) ->
                SchoolAuthRepository.CookieRecord(name = name, value = value, path = "/")
            }

    private fun normalizeCookieHeader(cookieHeader: String): String =
        cookieHeader
            .split(';')
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString(separator = "; ")

    private fun storageKey(suffix: String): String =
        "lrnw_AS_Parse/${SchoolAuthApi.LOGIN_APPLICATION_ID}/$suffix"

    private fun quote(value: String): String = JSONObject.quote(value)

    private companion object {
        private const val LOGIN_PAGE_HASH = "/#!/User/Index/login"
        private const val SPACE_LIST_HASH = "/#!/Space/Category/list"
        private val REQUIRED_COOKIE_NAMES =
            setOf(
                "org_id",
                "login_time",
                "api_access_token",
                "uid",
                "auth",
                "is_remember",
            )
    }
}
