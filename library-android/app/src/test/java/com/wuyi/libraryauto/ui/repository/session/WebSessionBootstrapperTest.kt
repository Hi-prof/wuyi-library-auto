package com.wuyi.libraryauto.ui.repository.session

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.core.network.auth.SessionBundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Test

class WebSessionBootstrapperTest {

    @Test
    fun `needsBootstrap returns true for api only cookies`() {
        val repository = FakeSessionRepository(apiSession())
        val bootstrapper = WebSessionBootstrapper(repository)

        assertThat(bootstrapper.needsBootstrap(apiSession())).isTrue()
    }

    @Test
    fun `needsBootstrap returns false when web cookies are already present`() {
        val repository = FakeSessionRepository(webSession())
        val bootstrapper = WebSessionBootstrapper(repository)

        assertThat(bootstrapper.needsBootstrap(webSession())).isFalse()
    }

    @Test
    fun `saveWebSession merges web cookies into existing api session`() {
        val repository = FakeSessionRepository(apiSession())
        val bootstrapper = WebSessionBootstrapper(repository)

        bootstrapper.saveWebSession(
            session = apiSession(),
            cookieHeader =
                "org_id=137; login_time=1775887200; uid=405963; auth=token; " +
                    "is_remember=1; web_language=zh-CN",
        )

        val savedSession = repository.currentSession()
        requireNotNull(savedSession)
        assertThat(savedSession.session.cookieHeader)
            .isEqualTo(
                "web_language=zh-CN; api_access_token=token; org_id=137; " +
                    "login_time=1775887200; uid=405963; auth=token; is_remember=1",
            )
        assertThat(savedSession.cookies.mapNotNull { it.name })
            .containsExactly(
                "web_language",
                "api_access_token",
                "org_id",
                "login_time",
                "uid",
                "auth",
                "is_remember",
            )
            .inOrder()
    }

    private fun apiSession(): AuthenticatedSession =
        AuthenticatedSession(
            session = SessionBundle(cookieHeader = "web_language=zh-CN; api_access_token=token", userId = "405963"),
            cookies = emptyList(),
            currentUserJson = """{"id":"405963","accessToken":"token"}""",
            origin = "https://wuyiu.huitu.zhishulib.com",
            installationId = "install-1",
        )

    private fun webSession(): AuthenticatedSession =
        apiSession().copy(
            session =
                SessionBundle(
                    cookieHeader =
                        "web_language=zh-CN; api_access_token=token; org_id=137; " +
                            "login_time=1775887200; uid=405963; auth=token; is_remember=1",
                    userId = "405963",
                ),
        )

    private class FakeSessionRepository(
        initialSession: AuthenticatedSession?,
    ) : SessionRepository {
        private val state = MutableStateFlow(initialSession)
        private var activeStudentId: String? = "405963"

        override val session: StateFlow<AuthenticatedSession?> = state.asStateFlow()

        override fun currentSession(): AuthenticatedSession? = state.value

        override fun currentSession(studentId: String): AuthenticatedSession? =
            state.value?.takeIf { activeStudentId == studentId }

        override fun activeStudentId(): String? = activeStudentId

        override fun activate(studentId: String): Boolean =
            state.value?.let {
                activeStudentId = studentId
                true
            } ?: false

        override fun save(session: AuthenticatedSession) {
            state.value = session
        }

        override fun save(
            studentId: String,
            session: AuthenticatedSession,
            activate: Boolean,
        ) {
            state.value = session
            if (activate) {
                activeStudentId = studentId
            }
        }

        override fun remove(studentId: String) {
            if (activeStudentId == studentId) {
                activeStudentId = null
                state.value = null
            }
        }

        override fun clear() {
            activeStudentId = null
            state.value = null
        }
    }
}
