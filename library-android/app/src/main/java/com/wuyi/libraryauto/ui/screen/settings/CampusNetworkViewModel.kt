package com.wuyi.libraryauto.ui.screen.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wuyi.libraryauto.core.network.captive.CampusPortalAuthRequest
import com.wuyi.libraryauto.core.network.captive.CampusPortalAuthResult
import com.wuyi.libraryauto.core.network.captive.CampusPortalAuthenticator
import com.wuyi.libraryauto.core.runtime.network.NetworkMonitorMetricsRepository
import com.wuyi.libraryauto.core.storage.network.CampusNetworkCredentialStore
import com.wuyi.libraryauto.ui.repository.SchoolPortalConfig
import com.wuyi.libraryauto.ui.repository.settings.ExecutionLogRepository
import kotlinx.coroutines.launch

class CampusNetworkViewModel(
    private val credentialStore: CampusNetworkCredentialStore,
    private val metricsRepository: NetworkMonitorMetricsRepository,
    private val executionLogRepository: ExecutionLogRepository,
    private val authenticator: CampusPortalAuthenticator,
    private val portalLoginPageUrlProvider: () -> String,
) : ViewModel() {
    var uiState by mutableStateOf(CampusNetworkUiState())
        private set

    init {
        refresh()
    }

    fun onUsernameChange(value: String) {
        uiState = uiState.copy(usernameInput = value)
    }

    fun onPasswordChange(value: String) {
        uiState = uiState.copy(passwordInput = value)
    }

    fun onLoginPageUrlChange(value: String) {
        uiState = uiState.copy(loginPageUrlInput = value)
    }

    fun saveCredential() {
        val username = uiState.usernameInput.trim()
        val password = uiState.passwordInput
        if (username.isEmpty() || password.isBlank()) {
            uiState = uiState.copy(actionMessage = "用户名和密码都不能为空")
            return
        }
        runCatching { credentialStore.save(username = username, password = password) }
            .onSuccess {
                runCatching { credentialStore.saveLoginPageUrl(uiState.loginPageUrlInput.trim()) }
                uiState =
                    uiState.copy(
                        passwordInput = "",
                        actionMessage = "校园网账号已保存",
                    )
                refresh()
            }
            .onFailure { error ->
                uiState = uiState.copy(actionMessage = "保存失败：${error.message ?: "未知错误"}")
            }
    }

    fun clearCredential() {
        credentialStore.clear()
        uiState =
            uiState.copy(
                passwordInput = "",
                usernameInput = "",
                actionMessage = "已清除本地校园网账号",
            )
        refresh()
    }

    fun authenticateNow() {
        val credential = credentialStore.read()
        if (credential == null) {
            uiState = uiState.copy(actionMessage = "请先保存校园网账号")
            return
        }
        val loginPageUrl = SchoolPortalConfig.resolveCampusPortalLoginPageUrl(
            uiState.loginPageUrlInput.ifBlank { portalLoginPageUrlProvider() },
        )
        if (loginPageUrl.isBlank()) {
            uiState = uiState.copy(actionMessage = "登录页 URL 为空，请先填写")
            return
        }
        uiState = uiState.copy(isAuthenticating = true, actionMessage = "正在认证…")
        viewModelScope.launch {
            val result =
                runCatching {
                    authenticator.authenticate(
                        CampusPortalAuthRequest(
                            loginPageUrl = loginPageUrl,
                            username = credential.username,
                            password = credential.password,
                        ),
                    )
                }
            uiState =
                uiState.copy(
                    isAuthenticating = false,
                    actionMessage =
                        result.fold(
                            onSuccess = { authResult -> authResult.toMessage() },
                            onFailure = { error -> "认证异常：${error.message ?: "未知错误"}" },
                        ),
                )
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            metricsRepository.refresh()
            val credential = credentialStore.read()
            val savedLoginPageUrl = credentialStore.readLoginPageUrl()
            val metrics = metricsRepository.metrics.value
            val events =
                executionLogRepository.loadAll()
                    .filter { item -> item.message.contains("校园网") || item.message.contains("认证") }
                    .take(5)
                    .map { item -> "${item.timestampLabel} ${item.message}" }
            uiState =
                uiState.copy(
                    enabled = credential != null,
                    currentSsid = metrics.currentSsid,
                    requiresCaptivePortal = metrics.requiresCaptivePortal,
                    maskedUsername = credential?.username?.maskSensitive().orEmpty().ifBlank { "未配置" },
                    usernameInput = credential?.username.orEmpty(),
                    loginPageUrlInput =
                        SchoolPortalConfig.resolveCampusPortalLoginPageUrl(
                            savedLoginPageUrl
                                ?: uiState.loginPageUrlInput.ifBlank { portalLoginPageUrlProvider() },
                        ),
                    recentAuthEvents = events,
                )
        }
    }

    private fun CampusPortalAuthResult.toMessage(): String =
        when (this) {
            is CampusPortalAuthResult.Success -> "认证成功：$message"
            is CampusPortalAuthResult.Failure -> "认证失败：$message"
            is CampusPortalAuthResult.Skipped -> "已跳过：$message"
        }
}

data class CampusNetworkUiState(
    val enabled: Boolean = false,
    val currentSsid: String = "未授权",
    val requiresCaptivePortal: Boolean = false,
    val maskedUsername: String = "未配置",
    val recentAuthEvents: List<String> = emptyList(),
    val usernameInput: String = "",
    val passwordInput: String = "",
    val loginPageUrlInput: String = "",
    val isAuthenticating: Boolean = false,
    val actionMessage: String? = null,
)

class CampusNetworkViewModelFactory(
    private val credentialStore: CampusNetworkCredentialStore,
    private val metricsRepository: NetworkMonitorMetricsRepository,
    private val executionLogRepository: ExecutionLogRepository,
    private val authenticator: CampusPortalAuthenticator,
    private val portalLoginPageUrlProvider: () -> String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(CampusNetworkViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        return CampusNetworkViewModel(
            credentialStore = credentialStore,
            metricsRepository = metricsRepository,
            executionLogRepository = executionLogRepository,
            authenticator = authenticator,
            portalLoginPageUrlProvider = portalLoginPageUrlProvider,
        ) as T
    }
}
