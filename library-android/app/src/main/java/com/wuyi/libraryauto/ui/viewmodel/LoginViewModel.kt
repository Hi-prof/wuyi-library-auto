package com.wuyi.libraryauto.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class LoginViewModel(
    private val loginGateway: LoginGateway,
) : ViewModel() {

    var uiState by mutableStateOf(LoginUiState())
        private set

    fun login(studentId: String, password: String) {
        val safeStudentId = studentId.trim()
        val safePassword = password.trim().ifEmpty { safeStudentId }
        if (safeStudentId.isEmpty()) {
            uiState = LoginUiState(errorMessage = "请输入学号和密码")
            return
        }

        if (uiState.isLoading) {
            return
        }

        viewModelScope.launch {
            uiState = LoginUiState(isLoading = true)
            uiState =
                when (val result = loginGateway.login(safeStudentId, safePassword)) {
                    LoginResult.Success -> LoginUiState(loggedIn = true)
                    is LoginResult.Failure -> LoginUiState(errorMessage = result.message)
                }
        }
    }

    fun clearError() {
        if (uiState.errorMessage != null) {
            uiState = uiState.copy(errorMessage = null)
        }
    }
}

data class LoginUiState(
    val loggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

fun interface LoginGateway {
    suspend fun login(studentId: String, password: String): LoginResult
}

sealed interface LoginResult {
    data object Success : LoginResult

    data class Failure(
        val message: String,
    ) : LoginResult
}

object DemoLoginGateway : LoginGateway {
    private const val DemoStudentId = "00000000"
    private const val DemoPassword = "demo"

    override suspend fun login(studentId: String, password: String): LoginResult {
        return if (studentId == DemoStudentId && password == DemoPassword) {
            LoginResult.Success
        } else {
            LoginResult.Failure("当前是演示登录壳，请使用演示账号进入页面壳")
        }
    }
}

class LoginViewModelFactory(
    private val loginGateway: LoginGateway,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(LoginViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        return LoginViewModel(loginGateway) as T
    }
}
