package com.wuyi.libraryauto.ui.viewmodel

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `uiState defaults to logged out without error`() {
        val viewModel = LoginViewModel(loginGateway = ImmediateLoginGateway.success())

        assertThat(viewModel.uiState.loggedIn).isFalse()
        assertThat(viewModel.uiState.isLoading).isFalse()
        assertThat(viewModel.uiState.errorMessage).isNull()
    }

    @Test
    fun `login shows loading while async gateway is running`() = runTest {
        val gateway = DeferredLoginGateway()
        val viewModel = LoginViewModel(loginGateway = gateway)

        viewModel.login(studentId = "20230001", password = "secret")

        assertThat(viewModel.uiState.isLoading).isTrue()
        assertThat(viewModel.uiState.loggedIn).isFalse()
        assertThat(viewModel.uiState.errorMessage).isNull()
        assertThat(gateway.lastStudentId).isEqualTo("20230001")
        assertThat(gateway.lastPassword).isEqualTo("secret")
    }

    @Test
    fun `login marks state as logged in when gateway succeeds`() = runTest {
        val gateway = DeferredLoginGateway()
        val viewModel = LoginViewModel(loginGateway = gateway)

        viewModel.login(studentId = "20230001", password = "secret")
        gateway.complete(LoginResult.Success)
        advanceUntilIdle()

        assertThat(viewModel.uiState.loggedIn).isTrue()
        assertThat(viewModel.uiState.isLoading).isFalse()
        assertThat(viewModel.uiState.errorMessage).isNull()
        assertThat(gateway.lastStudentId).isEqualTo("20230001")
        assertThat(gateway.lastPassword).isEqualTo("secret")
    }

    @Test
    fun `login stores chinese error message when gateway fails`() = runTest {
        val viewModel = LoginViewModel(loginGateway = ImmediateLoginGateway.failure("学号或密码不正确"))

        viewModel.login(studentId = "20230001", password = "wrong")
        advanceUntilIdle()

        assertThat(viewModel.uiState.loggedIn).isFalse()
        assertThat(viewModel.uiState.isLoading).isFalse()
        assertThat(viewModel.uiState.errorMessage).isEqualTo("学号或密码不正确")
    }

    @Test
    fun `login short circuits on empty input without calling gateway`() {
        val gateway = ImmediateLoginGateway.success()
        val viewModel = LoginViewModel(loginGateway = gateway)

        viewModel.login(studentId = "  ", password = "")

        assertThat(viewModel.uiState.loggedIn).isFalse()
        assertThat(viewModel.uiState.isLoading).isFalse()
        assertThat(viewModel.uiState.errorMessage).isEqualTo("请输入学号和密码")
        assertThat(gateway.callCount).isEqualTo(0)
    }

    @Test
    fun `login defaults blank password to student id`() = runTest {
        val gateway = ImmediateLoginGateway.success()
        val viewModel = LoginViewModel(loginGateway = gateway)

        viewModel.login(studentId = "20230001", password = " ")
        advanceUntilIdle()

        assertThat(viewModel.uiState.loggedIn).isTrue()
        assertThat(viewModel.uiState.isLoading).isFalse()
        assertThat(viewModel.uiState.errorMessage).isNull()
        assertThat(gateway.lastStudentId).isEqualTo("20230001")
        assertThat(gateway.lastPassword).isEqualTo("20230001")
    }

    @Test
    fun `login trims credentials before success`() = runTest {
        val gateway = ImmediateLoginGateway.success()
        val viewModel = LoginViewModel(loginGateway = gateway)

        viewModel.login(studentId = " 20230001 ", password = " secret ")
        advanceUntilIdle()

        assertThat(viewModel.uiState.loggedIn).isTrue()
        assertThat(gateway.lastStudentId).isEqualTo("20230001")
        assertThat(gateway.lastPassword).isEqualTo("secret")
    }

    @Test
    fun `login trims credentials before failure`() = runTest {
        val gateway = ImmediateLoginGateway.failure("学号或密码不正确")
        val viewModel = LoginViewModel(loginGateway = gateway)

        viewModel.login(studentId = " 20230001 ", password = " wrong ")
        advanceUntilIdle()

        assertThat(viewModel.uiState.loggedIn).isFalse()
        assertThat(viewModel.uiState.errorMessage).isEqualTo("学号或密码不正确")
        assertThat(gateway.lastStudentId).isEqualTo("20230001")
        assertThat(gateway.lastPassword).isEqualTo("wrong")
    }

    @Test
    fun `clearError removes current error`() = runTest {
        val viewModel = LoginViewModel(loginGateway = ImmediateLoginGateway.failure("学号或密码不正确"))

        viewModel.login(studentId = "20230001", password = "wrong")
        advanceUntilIdle()
        viewModel.clearError()

        assertThat(viewModel.uiState.loggedIn).isFalse()
        assertThat(viewModel.uiState.errorMessage).isNull()
    }

    private class ImmediateLoginGateway(
        private val result: LoginResult,
    ) : LoginGateway {

        var callCount: Int = 0
            private set
        var lastStudentId: String? = null
            private set
        var lastPassword: String? = null
            private set

        override suspend fun login(studentId: String, password: String): LoginResult {
            callCount += 1
            lastStudentId = studentId
            lastPassword = password
            return result
        }

        companion object {
            fun success(): ImmediateLoginGateway = ImmediateLoginGateway(LoginResult.Success)

            fun failure(message: String): ImmediateLoginGateway =
                ImmediateLoginGateway(LoginResult.Failure(message))
        }
    }

    private class DeferredLoginGateway : LoginGateway {
        private val deferred = CompletableDeferred<LoginResult>()

        var lastStudentId: String? = null
            private set
        var lastPassword: String? = null
            private set

        override suspend fun login(studentId: String, password: String): LoginResult {
            lastStudentId = studentId
            lastPassword = password
            return deferred.await()
        }

        fun complete(result: LoginResult) {
            deferred.complete(result)
        }
    }
}
