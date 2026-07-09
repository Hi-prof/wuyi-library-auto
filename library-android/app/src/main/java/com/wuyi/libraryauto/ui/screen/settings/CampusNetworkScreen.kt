package com.wuyi.libraryauto.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wuyi.libraryauto.core.network.captive.CampusPortalAuthenticator
import com.wuyi.libraryauto.core.runtime.network.NetworkMonitorMetricsRepository
import com.wuyi.libraryauto.core.storage.network.CampusNetworkCredentialStore
import com.wuyi.libraryauto.ui.repository.settings.ExecutionLogRepository

@Composable
fun CampusNetworkScreen(
    credentialStore: CampusNetworkCredentialStore,
    metricsRepository: NetworkMonitorMetricsRepository,
    executionLogRepository: ExecutionLogRepository,
    authenticator: CampusPortalAuthenticator,
    portalLoginPageUrlProvider: () -> String,
) {
    val factory =
        remember(
            credentialStore,
            metricsRepository,
            executionLogRepository,
            authenticator,
            portalLoginPageUrlProvider,
        ) {
            CampusNetworkViewModelFactory(
                credentialStore = credentialStore,
                metricsRepository = metricsRepository,
                executionLogRepository = executionLogRepository,
                authenticator = authenticator,
                portalLoginPageUrlProvider = portalLoginPageUrlProvider,
            )
        }
    val viewModel: CampusNetworkViewModel = viewModel(factory = factory)
    val state = viewModel.uiState

    SettingsLazyColumn {
        item {
            SettingsCard(title = "校园网认证", body = "保存账号后，后台周期签到与预约 Worker 会自动通过校园网认证。") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("已保存账号")
                    Switch(checked = state.enabled, onCheckedChange = null)
                }
                InfoLine("当前 SSID", state.currentSsid)
                InfoLine(
                    "校园网状态",
                    if (state.requiresCaptivePortal) "需要认证" else "已通过 / 未连入校园网",
                )
                InfoLine("已保存账号", state.maskedUsername)
            }
        }

        item {
            SettingsCard(
                title = "录入校园网账号",
                body = "账号密码加密保存到本地（独立 prefs 文件），不会上传任何外部服务器。",
            ) {
                OutlinedTextField(
                    value = state.usernameInput,
                    onValueChange = viewModel::onUsernameChange,
                    label = { Text("校园网用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.passwordInput,
                    onValueChange = viewModel::onPasswordChange,
                    label = { Text("校园网密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.loginPageUrlInput,
                    onValueChange = viewModel::onLoginPageUrlChange,
                    label = { Text("登录页 URL") },
                    supportingText = {
                        Text("默认指向校园门户认证页；学校改 URL 时手动修正这一行即可。")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = viewModel::saveCredential,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("保存账号")
                    }
                    OutlinedButton(
                        onClick = viewModel::clearCredential,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("清除账号")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = viewModel::authenticateNow,
                        enabled = !state.isAuthenticating && state.enabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (state.isAuthenticating) "认证中…" else "立即认证")
                    }
                    OutlinedButton(
                        onClick = viewModel::refresh,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("刷新状态")
                    }
                }
                state.actionMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SettingsCard(title = "最近认证事件", body = "最多展示最近 5 条校园网认证相关执行日志。") {
                if (state.recentAuthEvents.isEmpty()) {
                    Text("暂无记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    state.recentAuthEvents.forEach { event -> Text(event) }
                }
            }
        }
    }
}
