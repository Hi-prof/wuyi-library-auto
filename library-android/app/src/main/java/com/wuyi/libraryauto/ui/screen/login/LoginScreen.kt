package com.wuyi.libraryauto.ui.screen.login

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
    var studentId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LaunchedEffect(uiState.loggedIn) {
        if (uiState.loggedIn) {
            onLoginSuccess()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            LoginHero(title = title, description = description, hint = hint)
            Surface(
                color = MaterialTheme.colorScheme.surface,
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
                            Text("可留空，默认会使用学号", style = MaterialTheme.typography.labelSmall)
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
                    if (uiState.errorMessage != null) {
                        ErrorBanner(message = uiState.errorMessage)
                    }
                    Button(
                        onClick = { viewModel.login(studentId, password) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        enabled = !uiState.isLoading,
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
                        Text(if (uiState.isLoading) "登录中..." else primaryButtonLabel)
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
                        Text(secondaryButtonLabel)
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
private fun LoginHero(
    title: String,
    description: String,
    hint: String,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box {
            HeroIllustration(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(160.dp),
            )
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                )
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun HeroIllustration(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val container = MaterialTheme.colorScheme.surface
    val accent = MaterialTheme.colorScheme.tertiary
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // 右上角圆环装饰
        drawCircle(
            brush = Brush.radialGradient(listOf(primary.copy(alpha = 0.18f), Color.Transparent)),
            radius = w * 0.4f,
            center = Offset(w * 0.85f, h * 0.0f),
        )
        // 右侧白色"卡片"+小圆点
        drawRoundRect(
            color = container.copy(alpha = 0.9f),
            topLeft = Offset(w * 0.62f, h * 0.32f),
            size = Size(w * 0.3f, h * 0.5f),
            cornerRadius = CornerRadius(20f, 20f),
        )
        drawCircle(color = primary, radius = h * 0.04f, center = Offset(w * 0.7f, h * 0.45f))
        drawRoundRect(
            color = primary.copy(alpha = 0.6f),
            topLeft = Offset(w * 0.66f, h * 0.55f),
            size = Size(w * 0.22f, h * 0.05f),
            cornerRadius = CornerRadius(4f, 4f),
        )
        drawRoundRect(
            color = primary.copy(alpha = 0.4f),
            topLeft = Offset(w * 0.66f, h * 0.65f),
            size = Size(w * 0.18f, h * 0.05f),
            cornerRadius = CornerRadius(4f, 4f),
        )
        // 锁形状（简笔）
        drawRoundRect(
            color = primary,
            topLeft = Offset(w * 0.78f, h * 0.18f),
            size = Size(w * 0.06f, h * 0.13f),
            cornerRadius = CornerRadius(8f, 8f),
            style = Stroke(width = 3f),
        )
        // 闪光线
        listOf(0f, 1f, 2f).forEach { i ->
            val y = h * (0.15f + i * 0.06f)
            drawLine(
                color = accent.copy(alpha = 0.5f),
                start = Offset(w * 0.5f, y),
                end = Offset(w * 0.58f, y),
                strokeWidth = 2f,
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
