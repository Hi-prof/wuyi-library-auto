package com.wuyi.libraryauto.core.network.auth

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.Assert.assertThrows

class SchoolAuthRepositoryTest {

    private val repository = SchoolAuthRepository()

    @Test
    fun `parseSavedSession builds cookie header and reads current user id`() {
        val cookies =
            listOf(
                SchoolAuthRepository.CookieRecord(name = "PHPSESSID", value = "session-value"),
                SchoolAuthRepository.CookieRecord(name = "Hm_lvt", value = "analytics-value"),
            )

        val actual =
            repository.parseSavedSession(
                cookies = cookies,
                currentUserJson = """{"id": 9527, "name": "tester"}""",
            )

        assertThat(actual)
            .isEqualTo(
                SessionBundle(
                    cookieHeader = "PHPSESSID=session-value; Hm_lvt=analytics-value",
                    userId = "9527",
                )
            )
    }

    @Test
    fun `parseSavedSession throws clear error when cookies are missing`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                repository.parseSavedSession(
                    cookies = emptyList(),
                    currentUserJson = """{"id":"9527"}""",
                )
            }

        assertThat(error).hasMessageThat().isEqualTo("登录态中未找到可用 Cookie，请重新执行 save-login")
    }

    @Test
    fun `parseSavedSession throws clear error when user id is missing`() {
        val cookies = listOf(SchoolAuthRepository.CookieRecord(name = "PHPSESSID", value = "session"))

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                repository.parseSavedSession(
                    cookies = cookies,
                    currentUserJson = """{"name":"tester"}""",
                )
            }

        assertThat(error).hasMessageThat().isEqualTo("登录态中未找到用户 ID，请重新执行 save-login")
    }

    @Test
    fun `parseSavedSession throws clear error when user id is not scalar`() {
        val cookies = listOf(SchoolAuthRepository.CookieRecord(name = "PHPSESSID", value = "session"))

        val objectError =
            assertThrows(IllegalArgumentException::class.java) {
                repository.parseSavedSession(
                    cookies = cookies,
                    currentUserJson = """{"id":{"nested":"value"}}""",
                )
            }

        val arrayError =
            assertThrows(IllegalArgumentException::class.java) {
                repository.parseSavedSession(
                    cookies = cookies,
                    currentUserJson = """{"id":["9527"]}""",
                )
            }

        assertThat(objectError).hasMessageThat().isEqualTo("登录态中未找到用户 ID，请重新执行 save-login")
        assertThat(arrayError).hasMessageThat().isEqualTo("登录态中未找到用户 ID，请重新执行 save-login")
    }

    @Test
    fun `parseLoginMetadata extracts code str and org id`() {
        val actual =
            repository.parseLoginMetadata(
                """
                {
                  "content": {
                    "data": {
                      "code": "encoded-code",
                      "str": "encoded-str"
                    },
                    "itemHeader": {
                      "defaultData": {
                        "custom_value": "  org-9527 "
                      }
                    }
                  }
                }
                """.trimIndent()
            )

        assertThat(actual).isEqualTo(
            SchoolAuthRepository.LoginMetadata(
                code = "encoded-code",
                secret = "encoded-str",
                orgId = "org-9527",
            )
        )
    }

    @Test
    fun `buildLoginRequestBody keeps live browser compatible fields`() {
        val actual =
            repository.buildLoginRequestBody(
                studentId = "20240001",
                password = "secret",
                metadata =
                    SchoolAuthRepository.LoginMetadata(
                        code = "encoded-code",
                        secret = "encoded-str",
                        orgId = "org-9527",
                    ),
                installationId = "install-1",
            )

        assertThat(actual)
            .isEqualTo(
                """
                {"login_name":"20240001","password":"secret","ui_type":"com.Raw","code":"encoded-code","str":"encoded-str","org_id":"org-9527","_ApplicationId":"lab4","_JavaScriptKey":"lab4","_ClientVersion":"js_xxx","_InstallationId":"install-1","_SessionToken":"fake"}
                """.trimIndent()
            )
    }

    @Test
    fun `normalizeCurrentUserJson adds compatibility fields`() {
        val actual =
            repository.normalizeCurrentUserJson(
                """
                {
                  "id": 9527,
                  "name": "tester",
                  "accessToken": "token-1"
                }
                """.trimIndent()
            )

        assertThat(actual)
            .isEqualTo(
                """{"id":9527,"name":"tester","accessToken":"token-1","access_token":"token-1","objectId":"9527","sessionToken":"fake","className":"_User"}"""
            )
    }
}
