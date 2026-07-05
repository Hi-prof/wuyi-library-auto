package com.wuyi.libraryauto.ui.screen.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Help
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.LockReset
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wuyi.libraryauto.ui.components.StatusBadge
import com.wuyi.libraryauto.ui.viewmodel.LoginGateway
import com.wuyi.libraryauto.ui.viewmodel.LoginViewModel
import com.wuyi.libraryauto.ui.viewmodel.LoginViewModelFactory

/**
 * 登录页：渐变 hero + 自绘插画 + 56dp 圆角输入框 + 主操作 Button + 次级 TextButton。
 *
 * 不再使用 Card 套 Card 的"对话框感"布局，改成沉浸式垂直滚动布局；输入框失焦颜色用
 * surfaceContainerHigh，焦点用 primary，无需深色边框。
 */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onOpenPermissionHelp: () -> Unit,
    loginGateway: LoginGateway,
    title: String = "登录",
    description: String = "使用学校账号完成真实认证。登录成功后会保存凭据，并把当前会话共享给查座位页复用。",
    hint: String = "如果登录失败，会直接显示学校接口返回的中文错误信息。",
    primaryButtonLabel: String = "登录并进入任务",
    secondaryButtonLabel: String = "先查看权限说明",
) {
    val viewModel: LoginViewModel = viewModel(factory = LoginViewModelFactory(loginGateway))
    val uiState = viewModel.uiState
    val presentation =
        buildLoginPresentation(
            uiState = uiState,
            title = title,
            description = description,
            hint = hint,
            primaryButtonLabel = primaryButtonLabel,
            secondaryButtonLabel = secondaryButtonLabel,
        )
    var studentId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LaunchedEffect(uiState.loggedIn) {
        if (uiState.loggedIn) {
            onLoginSuccess()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            LoginHeader(presentation = presentation)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    OutlinedTextField(
                        value = studentId,
                        onValueChange = {
                            studentId = it
                            viewModel.clearError()
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Badge,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        label = { Text("学号") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Next,
                            ),
                        colors = loginInputColors(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            viewModel.clearError()
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.VpnKey,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        label = { Text("密码") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = {
                            Text(presentation.passwordSupportText, style = MaterialTheme.typography.labelSmall)
                        },
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                        keyboardActions =
                            KeyboardActions(
                                onDone = { viewModel.login(studentId, password) },
                            ),
                        colors = loginInputColors(),
                    )
                    if (presentation.errorMessage != null) {
                        ErrorBanner(message = presentation.errorMessage)
                    }
                    Button(
                        onClick = { viewModel.login(studentId, password) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        enabled = presentation.primaryButtonEnabled,
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(vertical = 14.dp),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.LockReset,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(presentation.primaryButtonLabel)
                    }
                    TextButton(
                        onClick = onOpenPermissionHelp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Help,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(presentation.secondaryButtonLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun loginInputColors() =
    OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    )

@Composable
private fun LoginHeader(presentation: LoginPresentation) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusBadge(
                text = presentation.statusLabel,
                tone = presentation.statusTone,
            )
            Text(
                text = presentation.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = presentation.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = presentation.hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Outlined.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
