package com.wuyi.libraryauto.ui.repository.auth

import com.wuyi.libraryauto.core.network.auth.SchoolAuthService
import com.wuyi.libraryauto.core.network.auth.toLoginErrorMessage
import com.wuyi.libraryauto.core.storage.credentials.CredentialStore
import com.wuyi.libraryauto.core.storage.credentials.SavedAccountStore
import com.wuyi.libraryauto.ui.repository.SchoolPortalConfig
import com.wuyi.libraryauto.ui.repository.settings.LoginAuditRepository
import com.wuyi.libraryauto.ui.repository.session.SessionRepository
import com.wuyi.libraryauto.ui.viewmodel.LoginGateway
import com.wuyi.libraryauto.ui.viewmodel.LoginResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SchoolLoginGateway(
    private val authService: SchoolAuthService,
    private val credentialStore: CredentialStore,
    private val savedAccountStore: SavedAccountStore,
    private val sessionRepository: SessionRepository,
    private val loginAuditRepository: LoginAuditRepository? = null,
    private val loginUrl: String = SchoolPortalConfig.LoginUrl,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * 登录成功回调：用于通知应用层立即对该账号做一次远端预约同步并入队 GuardWorker，
     * 避免新登录的账号要等 30 分钟周期才被发现已有预约。回调失败不影响登录结果。
     */
    private val onLoginSucceeded: ((String) -> Unit)? = null,
) : LoginGateway {

    override suspend fun login(studentId: String, password: String): LoginResult =
        withContext(ioDispatcher) {
            loginAuditRepository?.recordAttempt(studentId = studentId, loginUrl = loginUrl)
            runCatching {
                authService.login(
                    loginUrl = loginUrl,
                    studentId = studentId,
                    password = password,
                )
            }.fold(
                onSuccess = { session ->
                    credentialStore.save(studentId = studentId, password = password)
                    savedAccountStore.save(studentId = studentId, password = password)
                    sessionRepository.save(studentId = studentId, session = session)
                    loginAuditRepository?.recordSuccess(studentId = studentId, loginUrl = loginUrl)
                    runCatching { onLoginSucceeded?.invoke(studentId) }
                    LoginResult.Success
                },
                onFailure = { error ->
                    val message = error.toLoginErrorMessage()
                    loginAuditRepository?.recordFailure(
                        studentId = studentId,
                        loginUrl = loginUrl,
                        message = message,
                    )
                    LoginResult.Failure(message)
                },
            )
        }
}
