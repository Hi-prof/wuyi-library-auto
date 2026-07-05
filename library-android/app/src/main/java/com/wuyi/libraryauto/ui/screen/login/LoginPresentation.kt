package com.wuyi.libraryauto.ui.screen.login

import com.wuyi.libraryauto.ui.components.StatusTone
import com.wuyi.libraryauto.ui.viewmodel.LoginUiState

internal data class LoginPresentation(
    val title: String,
    val description: String,
    val hint: String,
    val statusLabel: String,
    val statusTone: StatusTone,
    val primaryButtonLabel: String,
    val primaryButtonEnabled: Boolean,
    val secondaryButtonLabel: String,
    val passwordSupportText: String,
    val errorMessage: String?,
)

internal fun buildLoginPresentation(
    uiState: LoginUiState,
    title: String = "登录",
    description: String = "使用学校账号完成真实认证。登录成功后会保存凭据，并把当前会话共享给查座位页复用。",
    hint: String = "如果登录失败，会直接显示学校接口返回的中文错误信息。",
    primaryButtonLabel: String = "登录并进入任务",
    secondaryButtonLabel: String = "先查看权限说明",
): LoginPresentation =
    LoginPresentation(
        title = title,
        description = description,
        hint = hint,
        statusLabel =
            when {
                uiState.loggedIn -> "已登录"
                uiState.isLoading -> "正在验证"
                uiState.errorMessage != null -> "需要处理"
                else -> "待登录"
            },
        statusTone =
            when {
                uiState.loggedIn -> StatusTone.Positive
                uiState.isLoading -> StatusTone.Info
                uiState.errorMessage != null -> StatusTone.Negative
                else -> StatusTone.Neutral
            },
        primaryButtonLabel = if (uiState.isLoading) "登录中..." else primaryButtonLabel,
        primaryButtonEnabled = !uiState.isLoading && !uiState.loggedIn,
        secondaryButtonLabel = secondaryButtonLabel,
        passwordSupportText = "可留空，默认使用学号",
        errorMessage = uiState.errorMessage,
    )
