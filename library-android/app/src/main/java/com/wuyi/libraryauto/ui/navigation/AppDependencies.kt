package com.wuyi.libraryauto.ui.navigation

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import com.wuyi.libraryauto.core.domain.usecase.BuildContinuousReservationWindowsUseCase
import com.wuyi.libraryauto.core.domain.usecase.TriggerSource
import com.wuyi.libraryauto.core.network.auth.SchoolAuthService
import com.wuyi.libraryauto.core.network.captive.CampusPortalAuthenticator
import com.wuyi.libraryauto.core.network.captive.OkHttpTargetReachabilityProbe
import com.wuyi.libraryauto.core.network.seat.CookieSchoolSeatApi
import com.wuyi.libraryauto.core.network.seat.SeatBookingActionService
import com.wuyi.libraryauto.core.network.seat.SeatBookingStatusService
import com.wuyi.libraryauto.core.network.seat.SeatLookupService
import com.wuyi.libraryauto.core.network.seat.SeatReservationService
import com.wuyi.libraryauto.core.runtime.diagnostics.LocalDiagnosticLogRepository
import com.wuyi.libraryauto.core.runtime.network.NetworkMonitorMetricsRepository
import com.wuyi.libraryauto.core.runtime.watchdog.WatchdogHeartbeatStore
import com.wuyi.libraryauto.core.runtime.watchdog.WatchdogStateStore
import com.wuyi.libraryauto.core.runtime.worker.AutomationPlanScheduler
import com.wuyi.libraryauto.core.runtime.worker.PeriodicCheckInWorker
import com.wuyi.libraryauto.core.runtime.worker.ReservationGuardWorker
import com.wuyi.libraryauto.core.storage.audit.BeaconScanAuditRepository
import com.wuyi.libraryauto.core.storage.audit.SignInAuditRepository
import com.wuyi.libraryauto.core.storage.credentials.CredentialStore
import com.wuyi.libraryauto.core.storage.credentials.SavedAccountStore
import com.wuyi.libraryauto.core.storage.db.StorageDatabaseProvider
import com.wuyi.libraryauto.core.storage.network.CampusNetworkCredentialStore
import com.wuyi.libraryauto.core.storage.network.WifiReconnectStore
import com.wuyi.libraryauto.sync.AccountPoolApi
import com.wuyi.libraryauto.sync.AccountPoolApiFactory
import com.wuyi.libraryauto.sync.AccountPoolSyncRepository
import com.wuyi.libraryauto.sync.AutomationPlanUploadService
import com.wuyi.libraryauto.sync.AutomationTaskUploader
import com.wuyi.libraryauto.sync.RoomLocalAccountStore
import com.wuyi.libraryauto.sync.ServerSyncConfig
import com.wuyi.libraryauto.sync.SyncStatusIndicator
import com.wuyi.libraryauto.ui.repository.account.StoredSavedAccountRepository
import com.wuyi.libraryauto.ui.repository.auth.SchoolLoginGateway
import com.wuyi.libraryauto.ui.repository.SchoolPortalConfig
import com.wuyi.libraryauto.ui.repository.settings.DiagnosticsLogRepository
import com.wuyi.libraryauto.ui.repository.settings.ExecutionLogRepository
import com.wuyi.libraryauto.ui.repository.settings.LoginAuditRepository
import com.wuyi.libraryauto.ui.repository.settings.SeatActionAuditRepository
import com.wuyi.libraryauto.ui.repository.settings.SeatLookupAuditRepository
import com.wuyi.libraryauto.ui.repository.settings.SeatStatusAuditRepository
import com.wuyi.libraryauto.ui.repository.seat.ManualReservationRepository
import com.wuyi.libraryauto.ui.repository.seat.NetworkSeatLookupRepository
import com.wuyi.libraryauto.ui.repository.seat.PersistentResolvedSeatUrlRepository
import com.wuyi.libraryauto.ui.repository.seat.ReservationGuardScheduler
import com.wuyi.libraryauto.ui.repository.seat.ResolvedSeatUrlRepository
import com.wuyi.libraryauto.ui.repository.seat.SeatDisplayRepository
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupRepository
import com.wuyi.libraryauto.ui.repository.session.PersistentSessionRepository
import com.wuyi.libraryauto.ui.repository.session.SessionRepository
import com.wuyi.libraryauto.ui.repository.task.AccountOperationCoordinator
import com.wuyi.libraryauto.ui.repository.task.AccountReservationHistoryReader
import com.wuyi.libraryauto.ui.repository.task.AccountReservationHistoryReaderImpl
import com.wuyi.libraryauto.ui.repository.task.AccountSeatActionRepository
import com.wuyi.libraryauto.ui.repository.task.AccountStatusRepository
import com.wuyi.libraryauto.ui.repository.task.AutomationPlanRepository
import com.wuyi.libraryauto.ui.repository.task.ExecutionLogBatchProgressWriter
import com.wuyi.libraryauto.ui.repository.task.RunPeriodicCheckInBatchRunner
import com.wuyi.libraryauto.ui.repository.task.StorageAutomationPlanRepository
import com.wuyi.libraryauto.ui.screen.settings.SignInMonitoringDataSource
import com.wuyi.libraryauto.ui.screen.settings.StorageSignInMonitoringDataSource
import com.wuyi.libraryauto.ui.viewmodel.SavedAccountRepository
import com.wuyi.libraryauto.ui.viewmodel.LoginGateway

internal class AppDependencies(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val appDatabase = StorageDatabaseProvider.get(appContext)
    private val credentialStore = CredentialStore(appContext)
    private val savedAccountStore = SavedAccountStore(appContext)
    private val loginAuditRepository = LoginAuditRepository(appContext)
    private val seatLookupAuditRepository = SeatLookupAuditRepository(appContext)
    private val seatStatusAuditRepository = SeatStatusAuditRepository(appContext)
    private val seatActionAuditRepository = SeatActionAuditRepository(appContext)
    private val localDiagnosticLogRepository = LocalDiagnosticLogRepository(appContext)
    private val signInAuditRepository = SignInAuditRepository(appDatabase.signInAuditDao())
    private val beaconScanAuditRepository = BeaconScanAuditRepository(appDatabase.beaconScanAuditDao())
    private val seatLookupService = SeatLookupService()
    private val seatReservationService = SeatReservationService()
    private val resolvedSeatUrlRepository: ResolvedSeatUrlRepository = PersistentResolvedSeatUrlRepository(appContext)
    private val storedSavedAccountRepository = StoredSavedAccountRepository(savedAccountStore)
    val campusNetworkCredentialStore = CampusNetworkCredentialStore(appContext)
    /** 复用 [CampusPortalRecoveryRunnerFactory] 持有的单例认证器，确保 UI 与后台 Worker 使用同一冷却状态。 */
    val campusPortalAuthenticator: CampusPortalAuthenticator =
        com.wuyi.libraryauto.runtime.CampusPortalRecoveryRunnerFactory.authenticator()
    val campusPortalLoginPageUrlProvider: () -> String = {
        SchoolPortalConfig.DefaultCampusPortalLoginPageUrl
    }
    val wifiReconnectStore = WifiReconnectStore(appContext)
    val watchdogHeartbeatStore = WatchdogHeartbeatStore(appContext)
    val watchdogStateStore = WatchdogStateStore(appContext)
    val networkMonitorMetricsRepository =
        NetworkMonitorMetricsRepository(
            context = appContext,
            wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager,
            connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager,
            probe = OkHttpTargetReachabilityProbe.default(),
        )
    val sessionRepository: SessionRepository = PersistentSessionRepository(appContext)
    val savedAccountRepository: SavedAccountRepository =
        storedSavedAccountRepository

    val loginGateway: LoginGateway =
        SchoolLoginGateway(
            authService = SchoolAuthService(),
            credentialStore = credentialStore,
            savedAccountStore = savedAccountStore,
            sessionRepository = sessionRepository,
            loginAuditRepository = loginAuditRepository,
            // 登录成功后立即触发一次 PeriodicCheckInWorker：内部会做远端预约同步 +
            // 入队 GuardWorker，让新登录的账号无需等 30 分钟就能感知已有预约。
            onLoginSucceeded = { _ ->
                PeriodicCheckInWorker.runOnceNow(appContext, TriggerSource.LoginSuccess)
            },
        )

    val seatLookupRepository: SeatLookupRepository =
        NetworkSeatLookupRepository(
            seatLookupService = seatLookupService,
            sessionRepository = sessionRepository,
            resolvedSeatUrlRepository = resolvedSeatUrlRepository,
            seatLookupAuditRepository = seatLookupAuditRepository,
        )

    val automationPlanScheduler = AutomationPlanScheduler(appContext)
    val accountOperationCoordinator = AccountOperationCoordinator()

    val manualReservationRepository =
        ManualReservationRepository(
            seatQueryGateway = seatLookupService,
            reservationGateway = seatReservationService,
            reservationTaskDao = appDatabase.reservationTaskDao(),
            executionLogDao = appDatabase.executionLogDao(),
            accountPreferenceWriter = storedSavedAccountRepository,
            sessionRepository = sessionRepository,
            guardScheduler =
                ReservationGuardScheduler { taskId, startTimeEpochSeconds, limitSignAgoSeconds ->
                    ReservationGuardWorker.enqueue(
                        context = appContext,
                        taskId = taskId,
                        startTimeEpochSeconds = startTimeEpochSeconds,
                        limitSignAgoSeconds = limitSignAgoSeconds,
                    )
                    Unit
                },
            resolvedSeatUrlRepository = resolvedSeatUrlRepository,
        )

    val automationPlanRepository: AutomationPlanRepository =
        StorageAutomationPlanRepository(
            automationPlanDao = appDatabase.automationPlanDao(),
            accountPreferenceWriter = storedSavedAccountRepository,
            scheduleWriter =
                object : com.wuyi.libraryauto.ui.repository.task.AutomationPlanScheduleWriter {
                    override fun schedule(
                        planId: String,
                        nextRunAtEpochSeconds: Long,
                    ) {
                        automationPlanScheduler.schedule(planId, nextRunAtEpochSeconds)
                    }

                    override fun cancel(planId: String) {
                        automationPlanScheduler.cancel(planId)
                    }
                },
            buildContinuousReservationWindowsUseCase = BuildContinuousReservationWindowsUseCase(),
        )

    val accountSeatActionRepository =
        AccountSeatActionRepository(
            accountSource = storedSavedAccountRepository,
            sessionRepository = sessionRepository,
            loginGateway = loginGateway,
            statusServiceFactory = { session ->
                SeatBookingStatusService(CookieSchoolSeatApi(session))
            },
            actionServiceFactory = { session ->
                SeatBookingActionService(CookieSchoolSeatApi(session))
            },
            coordinator = accountOperationCoordinator,
            seatServiceOrigins = listOf(SchoolPortalConfig.SeatServiceOrigin),
            seatStatusAuditRepository = seatStatusAuditRepository,
            seatActionAuditRepository = seatActionAuditRepository,
        )

    val accountStatusRepository =
        AccountStatusRepository(
            accountSource = storedSavedAccountRepository,
            sessionRepository = sessionRepository,
            automationPlanDao = appDatabase.automationPlanDao(),
            reservationTaskDao = appDatabase.reservationTaskDao(),
            accountSeatActionExecutor = accountSeatActionRepository,
        )

    val seatDisplayRepository =
        SeatDisplayRepository(
            accountRepository = savedAccountRepository,
            sessionRepository = sessionRepository,
            reservationTaskDao = appDatabase.reservationTaskDao(),
            seatDisplaySnapshotDao = appDatabase.seatDisplaySnapshotDao(),
            accountSeatActionExecutor = accountSeatActionRepository,
        )

    val batchCheckInRunner =
        RunPeriodicCheckInBatchRunner(
            accountSource = storedSavedAccountRepository,
            sessionRepository = sessionRepository,
            reservationTaskDao = appDatabase.reservationTaskDao(),
            accountSeatActionExecutor = accountSeatActionRepository,
            signInAuditRepository = signInAuditRepository,
        )

    val batchProgressWriter =
        ExecutionLogBatchProgressWriter(
            executionLogDao = appDatabase.executionLogDao(),
            reservationTaskDao = appDatabase.reservationTaskDao(),
        )

    val executionLogRepository = ExecutionLogRepository(appDatabase.executionLogDao())
    val signInMonitoringDataSource: SignInMonitoringDataSource =
        StorageSignInMonitoringDataSource(
            signInAuditRepository = signInAuditRepository,
            beaconScanAuditRepository = beaconScanAuditRepository,
        )
    val diagnosticsLogRepository =
        DiagnosticsLogRepository(
            executionLogRepository = executionLogRepository,
            loginAuditRepository = loginAuditRepository,
            seatStatusAuditRepository = seatStatusAuditRepository,
            seatLookupAuditRepository = seatLookupAuditRepository,
            seatActionAuditRepository = seatActionAuditRepository,
            localDiagnosticLogRepository = localDiagnosticLogRepository,
        )

    val accountReservationHistoryReader: AccountReservationHistoryReader =
        AccountReservationHistoryReaderImpl(
            planDao = appDatabase.automationPlanDao(),
            taskDao = appDatabase.reservationTaskDao(),
            snapshotDao = appDatabase.seatDisplaySnapshotDao(),
            storedAccountSource = storedSavedAccountRepository,
        )

    // ───── account-pool-tri-sync 任务 12.14：Manual_Sync_Action 入口装配 ─────
    /**
     * 同步配置（base_url / bearer_token / verify_tls / upload_enabled）。
     * 缺失关键字段时进入 Local_Only_Mode，不阻塞客户端启动。
     */
    val serverSyncConfig: ServerSyncConfig = ServerSyncConfig(appContext)

    /** 进程级单例：与 AutomationTaskUploadWorker 共享同一份按钮三态。 */
    val syncStatusIndicator: SyncStatusIndicator =
        SyncStatusIndicator.default().also { indicator ->
            indicator.updateConfigState(serverSyncConfig.isConfigured())
        }

    /**
     * 任务 12.14：[com.wuyi.libraryauto.sync.LocalAccountStore] 的生产实现，
     * 把受管字段写入 SavedAccountStore + ActiveAccountDao 组合存储。
     */
    val roomLocalAccountStore: RoomLocalAccountStore =
        RoomLocalAccountStore(
            database = appDatabase,
            savedAccountStore = savedAccountStore,
        )

    /**
     * 接口 A / 接口 B 调用的 Retrofit 客户端。
     *
     * 注：Manual_Sync_Action 触发时由 [com.wuyi.libraryauto.sync.ManualSyncCoverageViewModel] 驱动；
     * 未配置服务端时 ViewModel 内部的守卫会先做 [ServerSyncConfig.isConfigured] 检查，
     * 不会真正发起请求。这里使用占位 base URL 兜底，避免 Retrofit 在 ServerSyncConfig 缺失时抛
     * IllegalArgumentException 让 AppDependencies 整体构造失败。
     */
    private fun createAccountPoolApi(): AccountPoolApi =
        AccountPoolApiFactory.create(
            baseUrl =
                serverSyncConfig.baseUrl?.let { baseUrl -> baseUrl.trimEnd('/') + "/" }
                    ?: PLACEHOLDER_BASE_URL,
            tokenProvider = { serverSyncConfig.bearerToken },
        )

    /** Manual_Sync_Action 拉取 / 解析的仓库门面。 */
    val accountPoolSyncRepository: AccountPoolSyncRepository by lazy {
        AccountPoolSyncRepository(
            apiProvider = ::createAccountPoolApi,
            activeAccountDao = appDatabase.activeAccountDao(),
        )
    }

    /**
     * 自动任务上传入队器：把 PUT/DELETE 写入 [com.wuyi.libraryauto.core.storage.db.PendingTaskUploadDao]
     * 队列后由 [com.wuyi.libraryauto.sync.AutomationTaskUploadWorker] 后台执行。
     * 双开关守卫由 [ServerSyncConfig.isUploadEnabled] 自动兜底。
     */
    val automationTaskUploader: AutomationTaskUploader =
        AutomationTaskUploader(
            context = appContext,
            pendingDao = appDatabase.pendingTaskUploadDao(),
            serverSyncConfig = serverSyncConfig,
        )

    /** 「上传本地自动任务」按钮的业务入口（仿 Windows 端 upload_local_automation_plans_to_server）。 */
    val automationPlanUploadService: AutomationPlanUploadService =
        AutomationPlanUploadService(
            serverSyncConfig = serverSyncConfig,
            automationPlanDao = appDatabase.automationPlanDao(),
            accountPoolSyncRepository = accountPoolSyncRepository,
            uploader = automationTaskUploader,
        )

    /** 冲突记录 DAO：UI 侧观察 + 删除冲突。 */
    val taskUploadConflictDao = appDatabase.taskUploadConflictDao()

    /** 待发送队列 DAO：UI 侧观察「上传中 N 条」。 */
    val pendingTaskUploadDao = appDatabase.pendingTaskUploadDao()

    private companion object {
        /**
         * 占位 base URL：仅在 [ServerSyncConfig.baseUrl] 为空时让 Retrofit Builder 不抛错；
         * 实际网络调用永远受 [ManualSyncCoverageViewModel] 内的 [ServerSyncConfig.isConfigured]
         * 守卫保护，不会以此地址发起请求。
         */
        const val PLACEHOLDER_BASE_URL: String = "https://localhost/"
    }
}
