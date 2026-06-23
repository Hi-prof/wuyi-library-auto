package com.wuyi.libraryauto.ui.repository.session

import android.content.Context
import android.content.SharedPreferences
import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.core.network.auth.SchoolAuthRepository
import com.wuyi.libraryauto.core.network.auth.SessionBundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

interface SessionRepository {
    val session: StateFlow<AuthenticatedSession?>

    fun currentSession(): AuthenticatedSession?

    fun currentSession(studentId: String): AuthenticatedSession?

    fun activeStudentId(): String?

    fun activate(studentId: String): Boolean

    fun save(session: AuthenticatedSession)

    fun save(
        studentId: String,
        session: AuthenticatedSession,
        activate: Boolean = true,
    )

    fun remove(studentId: String)

    fun clear()
}

class InMemorySessionRepository : SessionRepository {
    private val sessionState = MutableStateFlow<AuthenticatedSession?>(null)
    private val sessionsByStudentId = linkedMapOf<String, AuthenticatedSession>()
    private var activeStudentId: String? = null

    override val session: StateFlow<AuthenticatedSession?> = sessionState.asStateFlow()

    override fun currentSession(): AuthenticatedSession? = sessionState.value

    override fun currentSession(studentId: String): AuthenticatedSession? =
        sessionsByStudentId[studentId.trim()]

    override fun activeStudentId(): String? = activeStudentId

    override fun activate(studentId: String): Boolean {
        val safeStudentId = studentId.trim()
        val savedSession = sessionsByStudentId[safeStudentId] ?: return false
        activeStudentId = safeStudentId
        sessionState.value = savedSession
        return true
    }

    override fun save(session: AuthenticatedSession) {
        val currentStudentId = activeStudentId
        if (currentStudentId != null) {
            save(studentId = currentStudentId, session = session, activate = true)
            return
        }
        sessionState.value = session
    }

    override fun save(
        studentId: String,
        session: AuthenticatedSession,
        activate: Boolean,
    ) {
        val safeStudentId = studentId.trim()
        if (safeStudentId.isBlank()) {
            return
        }
        sessionsByStudentId[safeStudentId] = session
        if (activate || activeStudentId == safeStudentId) {
            activeStudentId = safeStudentId
            sessionState.value = session
        }
    }

    override fun remove(studentId: String) {
        val safeStudentId = studentId.trim()
        if (safeStudentId.isBlank()) {
            return
        }
        sessionsByStudentId.remove(safeStudentId)
        if (activeStudentId == safeStudentId) {
            activeStudentId = null
            sessionState.value = null
        }
    }

    override fun clear() {
        sessionsByStudentId.clear()
        activeStudentId = null
        sessionState.value = null
    }
}

class PersistentSessionRepository(
    private val preferences: Lazy<SharedPreferences>,
) : SessionRepository {
    constructor(
        context: Context,
        preferencesName: String = DEFAULT_PREFERENCES_NAME,
    ) : this(
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        },
    )

    internal constructor(preferences: SharedPreferences) : this(lazyOf(preferences))

    private val sessionState = MutableStateFlow<AuthenticatedSession?>(null)
    private val sessionsByStudentId = linkedMapOf<String, AuthenticatedSession>()
    private var activeStudentId: String? = null

    override val session: StateFlow<AuthenticatedSession?> = sessionState.asStateFlow()

    init {
        restoreFromDisk()
    }

    override fun currentSession(): AuthenticatedSession? = sessionState.value

    override fun currentSession(studentId: String): AuthenticatedSession? =
        sessionsByStudentId[studentId.trim()]

    override fun activeStudentId(): String? = activeStudentId

    override fun activate(studentId: String): Boolean {
        val safeStudentId = studentId.trim()
        val savedSession = sessionsByStudentId[safeStudentId] ?: return false
        activeStudentId = safeStudentId
        sessionState.value = savedSession
        persistToDisk()
        return true
    }

    override fun save(session: AuthenticatedSession) {
        val currentStudentId = activeStudentId
        if (currentStudentId != null) {
            save(studentId = currentStudentId, session = session, activate = true)
            return
        }
        sessionState.value = session
    }

    override fun save(
        studentId: String,
        session: AuthenticatedSession,
        activate: Boolean,
    ) {
        val safeStudentId = studentId.trim()
        if (safeStudentId.isBlank()) {
            return
        }
        sessionsByStudentId[safeStudentId] = session
        if (activate || activeStudentId == safeStudentId) {
            activeStudentId = safeStudentId
            sessionState.value = session
        }
        persistToDisk()
    }

    override fun remove(studentId: String) {
        val safeStudentId = studentId.trim()
        if (safeStudentId.isBlank()) {
            return
        }
        sessionsByStudentId.remove(safeStudentId)
        if (activeStudentId == safeStudentId) {
            activeStudentId = null
            sessionState.value = null
        }
        persistToDisk()
    }

    override fun clear() {
        sessionsByStudentId.clear()
        activeStudentId = null
        sessionState.value = null
        persistToDisk()
    }

    private fun restoreFromDisk() {
        val rawSessions = preferences.value.getString(KEY_SESSIONS, null).orEmpty()
        if (rawSessions.isNotBlank()) {
            runCatching {
                decodeStoredSessions(rawSessions).forEach { (studentId, session) ->
                    sessionsByStudentId[studentId] = session
                }
            }.onFailure {
                // 会话损坏时直接丢弃，避免旧脏数据把账号列表永久卡成异常状态。
                preferences.value.edit().remove(KEY_SESSIONS).remove(KEY_ACTIVE_STUDENT_ID).apply()
                sessionsByStudentId.clear()
            }
        }

        val savedActiveStudentId = preferences.value.getString(KEY_ACTIVE_STUDENT_ID, null).orEmpty().trim()
        if (savedActiveStudentId.isNotBlank() && sessionsByStudentId.containsKey(savedActiveStudentId)) {
            activeStudentId = savedActiveStudentId
            sessionState.value = sessionsByStudentId[savedActiveStudentId]
        }
    }

    private fun persistToDisk() {
        preferences.value.edit()
            .putString(KEY_ACTIVE_STUDENT_ID, activeStudentId.orEmpty())
            .putString(KEY_SESSIONS, encodeStoredSessions())
            .apply()
    }

    private fun encodeStoredSessions(): String =
        sessionsByStudentId.entries.joinToString(SESSIONS_SEPARATOR) { (studentId, session) ->
            listOf(
                studentId,
                session.session.cookieHeader,
                session.session.userId,
                session.currentUserJson,
                session.origin,
                session.installationId,
                encodeCookies(session.cookies),
            ).joinToString(FIELD_SEPARATOR, transform = ::encodeValue)
        }

    private fun decodeStoredSessions(rawValue: String): List<Pair<String, AuthenticatedSession>> =
        rawValue
            .split(SESSIONS_SEPARATOR)
            .asSequence()
            .filter(String::isNotBlank)
            .mapNotNull { record ->
                val fields = record.split(FIELD_SEPARATOR)
                if (fields.size != SESSION_FIELD_COUNT) {
                    return@mapNotNull null
                }
                val decoded = fields.map(::decodeValue)
                val studentId = decoded[0].trim()
                val cookieHeader = decoded[1].trim()
                val userId = decoded[2].trim()
                val currentUserJson = decoded[3].trim()
                val origin = decoded[4].trim()
                val installationId = decoded[5].trim()
                if (
                    studentId.isBlank() ||
                    cookieHeader.isBlank() ||
                    userId.isBlank() ||
                    currentUserJson.isBlank() ||
                    origin.isBlank() ||
                    installationId.isBlank()
                ) {
                    return@mapNotNull null
                }
                studentId to
                    AuthenticatedSession(
                        session = SessionBundle(cookieHeader = cookieHeader, userId = userId),
                        cookies = decodeCookies(decoded[6]),
                        currentUserJson = currentUserJson,
                        origin = origin,
                        installationId = installationId,
                    )
            }
            .toList()

    private fun encodeCookies(cookies: List<SchoolAuthRepository.CookieRecord>): String =
        cookies.joinToString(COOKIES_SEPARATOR) { cookie ->
            listOf(
                cookie.name.orEmpty(),
                cookie.value.orEmpty(),
                cookie.domain.orEmpty(),
                cookie.path.orEmpty(),
            ).joinToString(COOKIE_FIELD_SEPARATOR, transform = ::encodeValue)
        }

    private fun decodeCookies(rawValue: String): List<SchoolAuthRepository.CookieRecord> =
        rawValue
            .split(COOKIES_SEPARATOR)
            .asSequence()
            .filter(String::isNotBlank)
            .mapNotNull { record ->
                val fields = record.split(COOKIE_FIELD_SEPARATOR)
                if (fields.size != COOKIE_FIELD_COUNT) {
                    return@mapNotNull null
                }
                val decoded = fields.map(::decodeValue)
                SchoolAuthRepository.CookieRecord(
                    name = decoded[0].ifBlank { null },
                    value = decoded[1].ifBlank { null },
                    domain = decoded[2].ifBlank { null },
                    path = decoded[3].ifBlank { null },
                )
            }
            .toList()

    private fun encodeValue(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun decodeValue(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private companion object {
        private const val DEFAULT_PREFERENCES_NAME = "library_auto_session_store"
        private const val KEY_ACTIVE_STUDENT_ID = "active_student_id"
        private const val KEY_SESSIONS = "sessions"
        private const val SESSIONS_SEPARATOR = "\n"
        private const val FIELD_SEPARATOR = "\t"
        private const val SESSION_FIELD_COUNT = 7
        private const val COOKIES_SEPARATOR = ","
        private const val COOKIE_FIELD_SEPARATOR = "~"
        private const val COOKIE_FIELD_COUNT = 4
    }
}
