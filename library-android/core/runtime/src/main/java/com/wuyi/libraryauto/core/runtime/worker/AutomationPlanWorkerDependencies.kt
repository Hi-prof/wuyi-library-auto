package com.wuyi.libraryauto.core.runtime.worker

import android.content.Context
import com.wuyi.libraryauto.core.domain.usecase.BuildContinuousReservationWindowsUseCase
import com.wuyi.libraryauto.core.domain.usecase.ReservationWindow
import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.core.network.seat.CookieSchoolSeatApi
import com.wuyi.libraryauto.core.network.auth.SchoolAuthService
import com.wuyi.libraryauto.core.network.seat.BookingDetail
import com.wuyi.libraryauto.core.network.seat.SeatBookingStatusService
import com.wuyi.libraryauto.core.network.seat.SeatLookupService
import com.wuyi.libraryauto.core.network.seat.SeatQueryGateway
import com.wuyi.libraryauto.core.network.seat.SeatReservationGateway
import com.wuyi.libraryauto.core.network.seat.SeatReservationService
import com.wuyi.libraryauto.core.network.seat.SearchPageContext
import com.wuyi.libraryauto.core.storage.credentials.SavedAccountStore
import com.wuyi.libraryauto.core.storage.db.AutomationPlanDao
import com.wuyi.libraryauto.core.storage.db.AutomationPlanEntity
import com.wuyi.libraryauto.core.storage.db.ExecutionLogDao
import com.wuyi.libraryauto.core.storage.db.ExecutionLogEntity
import com.wuyi.libraryauto.core.storage.db.ReservationTaskDao
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import com.wuyi.libraryauto.core.storage.db.StorageDatabaseProvider
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.wuyi.libraryauto.core.network.seat.SeatLookupRepository as CoreSeatLookupRepository

internal data class AutomationPlanReservationResult(
    val bookingId: String,
    val roomName: String,
    val seatNumber: String,
    val message: String,
)

internal interface AutomationPlanWorkerDependencies {
    suspend fun findPlan(planId: String): AutomationPlanEntity?

    fun loadSavedAccount(studentId: String): SavedAccountStore.SavedAccount?

    fun login(
        studentId: String,
        password: String,
    ): AuthenticatedSession

    fun buildContinuousWindows(nowEpochSeconds: Long): List<ReservationWindow>

    fun loadBookedDates(
        plan: AutomationPlanEntity,
        session: AuthenticatedSession,
    ): Set<String>

    fun reserveSeat(
        plan: AutomationPlanEntity,
        session: AuthenticatedSession,
        beginTimeEpochSeconds: Int,
        durationSeconds: Int,
    ): AutomationPlanReservationResult

    fun loadBookingDetail(
        session: AuthenticatedSession,
        bookingId: String,
    ): BookingDetail?

    suspend fun pauseBetweenReservationAttempts()

    suspend fun pauseBeforeReservationRetry(error: Throwable)

    suspend fun upsertReservationTask(task: ReservationTaskEntity)

    suspend fun insertExecutionLog(log: ExecutionLogEntity)

    suspend fun updatePlan(plan: AutomationPlanEntity)

    fun schedule(
        planId: String,
        nextRunAtEpochSeconds: Long,
    )

    fun enqueueGuard(
        taskId: String,
        startTimeEpochSeconds: Long,
        limitSignAgoSeconds: Long,
    )
}

internal object AutomationPlanWorkerProvider {
    @Volatile
    private var factory: (Context) -> AutomationPlanWorkerDependencies = { context ->
        StorageAutomationPlanWorkerDependencies(context.applicationContext)
    }

    fun install(factory: (Context) -> AutomationPlanWorkerDependencies) {
        this.factory = factory
    }

    fun get(context: Context): AutomationPlanWorkerDependencies = factory(context)
}

private class StorageAutomationPlanWorkerDependencies(
    context: Context,
    private val appContext: Context = context.applicationContext,
    private val automationPlanDao: AutomationPlanDao = StorageDatabaseProvider.get(appContext).automationPlanDao(),
    private val executionLogDao: ExecutionLogDao = StorageDatabaseProvider.get(appContext).executionLogDao(),
    private val reservationTaskDao: ReservationTaskDao = StorageDatabaseProvider.get(appContext).reservationTaskDao(),
    private val savedAccountStore: SavedAccountStore = SavedAccountStore(appContext),
    private val authService: SchoolAuthService = SchoolAuthService(),
    private val seatQueryGateway: SeatQueryGateway = SeatLookupService(),
    private val reservationGateway: SeatReservationGateway = SeatReservationService(),
    private val entryUrls: List<String> = listOf(DEFAULT_ENTRY_URL),
    private val seatServiceOrigins: List<String> = listOf(DEFAULT_SEAT_SERVICE_ORIGIN),
    private val scheduler: AutomationPlanScheduler = AutomationPlanScheduler(appContext),
) : AutomationPlanWorkerDependencies {
    override suspend fun findPlan(planId: String): AutomationPlanEntity? = automationPlanDao.findById(planId)

    override fun loadSavedAccount(studentId: String): SavedAccountStore.SavedAccount? =
        savedAccountStore.readAll().firstOrNull { account -> account.studentId == studentId }

    override fun login(
        studentId: String,
        password: String,
    ): AuthenticatedSession = authService.login(DEFAULT_ENTRY_URL, studentId, password)

    override fun buildContinuousWindows(nowEpochSeconds: Long): List<ReservationWindow> =
        BuildContinuousReservationWindowsUseCase()(
            Instant.ofEpochSecond(nowEpochSeconds).atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime(),
        )

    override fun loadBookedDates(
        plan: AutomationPlanEntity,
        session: AuthenticatedSession,
    ): Set<String> {
        val statusService = SeatBookingStatusService(CookieSchoolSeatApi(session))
        for (origin in buildSeatServiceOrigins(session)) {
            runCatching {
                return statusService.loadActiveBookingDates(buildBookingListUrl(origin))
            }
        }
        return emptySet()
    }

    override fun reserveSeat(
        plan: AutomationPlanEntity,
        session: AuthenticatedSession,
        beginTimeEpochSeconds: Int,
        durationSeconds: Int,
    ): AutomationPlanReservationResult {
        val matchedSeat =
            entryUrls.firstNotNullOfOrNull { entryUrl ->
                val searchApiUrl =
                    seatQueryGateway.resolveSearchApiUrl(
                        entryUrl = entryUrl,
                        session = session.session,
                    )
                val searchPage =
                    seatQueryGateway.fetchSearchPage(
                        searchApiUrl = searchApiUrl,
                        session = session.session,
                    )
                // BUG-FIX: 连续预约模式下 BuildContinuousReservationWindowsUseCase 生成的窗口
                // 时长是 startHour 到 22:00 的完整区间（可达14小时），但学校接口有 max_duration
                // 限制（通常4-6小时）。传入超限 duration 会导致搜索不到座位，持续预约永远失败。
                // 对齐 Windows 端行为：解析 searchPage 的 range.max_duration，动态截断 duration。
                val effectiveDurationSeconds = clampDurationToSearchPageMax(
                    searchPage = searchPage,
                    requestedDurationSeconds = durationSeconds,
                )
                val searchResult =
                    seatQueryGateway.searchSeats(
                        searchApiUrl = searchApiUrl,
                        session = session.session,
                        formFields =
                            CoreSeatLookupRepository.buildCustomSearchFormPayload(
                                context = searchPage,
                                beginTime = beginTimeEpochSeconds,
                                durationSeconds = effectiveDurationSeconds,
                                peopleCount = 1,
                            ),
                    )
                val room =
                    searchResult.roomMaps.firstOrNull { item -> item.roomName == plan.roomName }
                        ?: searchResult.selectedRoom.takeIf { item -> item.roomName == plan.roomName }
                        ?: return@firstNotNullOfOrNull null
                val seat =
                    room.seats.firstOrNull { item ->
                        item.seatNumber == plan.seatNumber && item.selectable
                    } ?: return@firstNotNullOfOrNull null
                ResolvedSeat(
                    searchApiUrl = searchApiUrl,
                    roomId = room.roomId,
                    roomName = room.roomName,
                    seatId = seat.seatId,
                    seatNumber = seat.seatNumber,
                    effectiveDurationSeconds = effectiveDurationSeconds,
                )
            } ?: throw IllegalArgumentException("${plan.roomName} ${plan.seatNumber} 号座位当前不可预约，请稍后重试。")
        val receipt =
            reservationGateway.reserveSeat(
                searchApiUrl = matchedSeat.searchApiUrl,
                session = session.session,
                seatId = matchedSeat.seatId,
                roomId = matchedSeat.roomId,
                roomName = matchedSeat.roomName,
                seatNumber = matchedSeat.seatNumber,
                beginTime = beginTimeEpochSeconds,
                durationSeconds = matchedSeat.effectiveDurationSeconds,
            )
        return AutomationPlanReservationResult(
            bookingId = receipt.bookingId,
            roomName = matchedSeat.roomName,
            seatNumber = matchedSeat.seatNumber,
            message = receipt.message,
        )
    }

    override fun loadBookingDetail(
        session: AuthenticatedSession,
        bookingId: String,
    ): BookingDetail? {
        val statusService = SeatBookingStatusService(CookieSchoolSeatApi(session))
        buildSeatServiceOrigins(session).forEach { origin ->
            runCatching {
                statusService.loadBookingDetail(buildBookingListUrl(origin), bookingId)
            }.getOrNull()?.let { return it }
        }
        return null
    }

    override suspend fun pauseBetweenReservationAttempts() {
        delay(BETWEEN_ATTEMPTS_DELAY_MILLIS)
    }

    override suspend fun pauseBeforeReservationRetry(error: Throwable) {
        val delayMillis =
            if (AutomationPlanReservationRetryPolicy.isRateLimitFailure(error)) {
                RATE_LIMIT_RETRY_DELAY_MILLIS
            } else {
                BETWEEN_ATTEMPTS_DELAY_MILLIS
            }
        delay(delayMillis)
    }

    override suspend fun upsertReservationTask(task: ReservationTaskEntity) {
        reservationTaskDao.upsert(task)
    }

    override suspend fun insertExecutionLog(log: ExecutionLogEntity) {
        executionLogDao.insert(log)
    }

    override suspend fun updatePlan(plan: AutomationPlanEntity) {
        automationPlanDao.upsert(plan)
    }

    override fun schedule(
        planId: String,
        nextRunAtEpochSeconds: Long,
    ) {
        scheduler.schedule(planId, nextRunAtEpochSeconds)
    }

    override fun enqueueGuard(
        taskId: String,
        startTimeEpochSeconds: Long,
        limitSignAgoSeconds: Long,
    ) {
        ReservationGuardWorker.enqueue(
            context = appContext,
            taskId = taskId,
            startTimeEpochSeconds = startTimeEpochSeconds,
            limitSignAgoSeconds = limitSignAgoSeconds,
        )
    }

    private data class ResolvedSeat(
        val searchApiUrl: String,
        val roomId: String,
        val roomName: String,
        val seatId: String,
        val seatNumber: String,
        val effectiveDurationSeconds: Int,
    )

    private fun buildSeatServiceOrigins(session: AuthenticatedSession): List<String> =
        buildList {
            add(session.origin)
            addAll(seatServiceOrigins)
        }.map(String::trim)
            .filter(String::isNotBlank)
            .distinct()

    private fun buildBookingListUrl(origin: String): String =
        com.wuyi.libraryauto.core.network.seat.SchoolSeatApi.appendLabJson(
            "${origin.removeSuffix("/")}${com.wuyi.libraryauto.core.network.seat.SchoolSeatApi.MY_BOOKING_LIST_PATH}",
        )

    private companion object {
        private const val DEFAULT_ENTRY_URL = "https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list"
        private const val DEFAULT_SEAT_SERVICE_ORIGIN = "https://wuyiu.huitu.zhishulib.com"
        private const val BETWEEN_ATTEMPTS_DELAY_MILLIS = 3_000L
        private const val RATE_LIMIT_RETRY_DELAY_MILLIS = 20_000L

        /**
         * 对齐 Windows 端 `build_automation_future_default_filters` / `build_current_day_reserve_window`：
         * 解析 searchPage 的 `data.range.max_duration`，把 worker 传入的超长 duration 截断到
         * 学校允许的最大时长。如果解析失败（接口字段变动），回退到原始值让学校接口自己报错，
         * 避免逻辑静默降级。
         */
        private fun clampDurationToSearchPageMax(
            searchPage: SearchPageContext,
            requestedDurationSeconds: Int,
        ): Int {
            val maxDurationHours = parseMaxDurationFromPayload(searchPage.rawPayload)
                ?: return requestedDurationSeconds
            val maxDurationSeconds = maxDurationHours * 3600
            return if (requestedDurationSeconds > maxDurationSeconds) {
                maxDurationSeconds
            } else {
                requestedDurationSeconds
            }
        }

        /**
         * 从 searchPage rawPayload JSON 中解析 `data.range.max_duration`（小时数）。
         * 与 Windows 端 `int(range_data["max_duration"])` 对齐。
         */
        private fun parseMaxDurationFromPayload(rawPayload: String): Int? =
            runCatching {
                val root = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .parseToJsonElement(rawPayload)
                    .jsonObject
                val range = root["data"]?.jsonObject?.get("range")?.jsonObject ?: return@runCatching null
                val maxDuration = (range["max_duration"] as? JsonPrimitive)
                    ?.content?.trim()?.toIntOrNull()
                maxDuration
            }.getOrNull()
    }
}
