package com.wuyi.libraryauto.ui.repository.seat

import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import com.wuyi.libraryauto.core.domain.model.CheckInWindow
import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.core.network.seat.BookingDetail
import com.wuyi.libraryauto.core.network.seat.CookieSchoolSeatApi
import com.wuyi.libraryauto.core.network.seat.SchoolSeatApi
import com.wuyi.libraryauto.core.network.seat.SeatBookingStatusService
import com.wuyi.libraryauto.core.network.seat.SeatQueryGateway
import com.wuyi.libraryauto.core.network.seat.SeatReservationGateway
import com.wuyi.libraryauto.core.network.seat.SeatLookupResult
import com.wuyi.libraryauto.core.storage.db.ExecutionLogDao
import com.wuyi.libraryauto.core.storage.db.ExecutionLogEntity
import com.wuyi.libraryauto.core.storage.db.ReservationTaskDao
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import com.wuyi.libraryauto.core.storage.db.buildReservationTaskId
import com.wuyi.libraryauto.ui.repository.SchoolPortalConfig
import com.wuyi.libraryauto.ui.repository.session.SessionRepository
import com.wuyi.libraryauto.ui.repository.task.AccountPreferenceWriter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wuyi.libraryauto.core.network.seat.SeatLookupRepository as CoreSeatLookupRepository

data class ManualReservationSelection(
    val studentId: String,
    val entryUrl: String,
    val roomId: String,
    val roomName: String,
    val seatNumber: String,
    val beginTimeEpochSeconds: Int,
    val durationSeconds: Int,
    val peopleCount: Int = 1,
)

sealed interface ManualReservationResult {
    data class Success(
        val taskId: String,
        val bookingId: String,
        val message: String,
    ) : ManualReservationResult

    data class Failure(
        val message: String,
    ) : ManualReservationResult

    data object NotLoggedIn : ManualReservationResult
}

fun interface ManualReservationGateway {
    suspend fun reserve(selection: ManualReservationSelection): ManualReservationResult
}

fun interface ReservationGuardScheduler {
    fun schedule(
        taskId: String,
        startTimeEpochSeconds: Long,
        limitSignAgoSeconds: Long,
    )
}

fun interface ManualBookingDetailLoader {
    fun load(
        session: AuthenticatedSession,
        bookingId: String,
    ): BookingDetail?
}

class ManualReservationRepository(
    private val seatQueryGateway: SeatQueryGateway,
    private val reservationGateway: SeatReservationGateway,
    private val reservationTaskDao: ReservationTaskDao,
    private val executionLogDao: ExecutionLogDao? = null,
    private val accountPreferenceWriter: AccountPreferenceWriter,
    private val sessionRepository: SessionRepository,
    private val guardScheduler: ReservationGuardScheduler,
    private val entryUrls: List<String> = SchoolPortalConfig.SeatEntryUrls,
    private val resolvedSeatUrlRepository: ResolvedSeatUrlRepository? = null,
    private val bookingDetailLoader: ManualBookingDetailLoader = SeatStatusManualBookingDetailLoader(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ManualReservationGateway {
    override suspend fun reserve(selection: ManualReservationSelection): ManualReservationResult =
        withContext(ioDispatcher) {
            val session = sessionRepository.currentSession(selection.studentId)
                ?: return@withContext ManualReservationResult.NotLoggedIn

            runCatching {
                val resolvedLookup =
                    resolveLookupForReservation(
                        selection = selection,
                        session = session.session,
                    )
                val searchApiUrl = resolvedLookup.searchApiUrl
                val lookupResult = resolvedLookup.lookupResult
                val targetRoom =
                    lookupResult.roomMaps.firstOrNull { room -> room.roomId == selection.roomId }
                        ?: lookupResult.selectedRoom
                val targetSeat =
                    targetRoom.seats.firstOrNull { seat ->
                        seat.seatNumber == selection.seatNumber && seat.selectable
                    } ?: throw IllegalArgumentException("所选座位当前不可预约，请刷新后重试。")
                val receipt =
                    reservationGateway.reserveSeat(
                        searchApiUrl = searchApiUrl,
                        session = session.session,
                        seatId = targetSeat.seatId,
                        roomId = targetRoom.roomId,
                        roomName = targetRoom.roomName.ifBlank { selection.roomName },
                        seatNumber = targetSeat.seatNumber,
                        beginTime = selection.beginTimeEpochSeconds,
                        durationSeconds = selection.durationSeconds,
                    )
                val taskId = buildReservationTaskId(selection.studentId, receipt.bookingId)
                val taskSync = loadTaskSync(
                    session = session,
                    bookingId = receipt.bookingId,
                )
                val task =
                    ReservationTaskEntity(
                        id = taskId,
                        studentId = selection.studentId,
                        roomName = targetRoom.roomName.ifBlank { selection.roomName },
                        seatNumber = targetSeat.seatNumber,
                        state = ReservationTaskState.RESERVED_WAITING_SIGNIN,
                        bookingId = receipt.bookingId,
                        startTimeEpochSeconds = selection.beginTimeEpochSeconds.toLong(),
                        limitSignAgoSeconds = taskSync.limitSignAgoSeconds,
                        limitSignBackSeconds = taskSync.limitSignBackSeconds,
                        expectedMinorsCsv = taskSync.expectedMinorsCsv,
                        lastError = null,
                    )
                reservationTaskDao.upsert(task)
                executionLogDao?.insert(
                    ExecutionLogEntity(
                        taskId = task.id,
                        state = task.state,
                        recordedAtEpochSeconds = System.currentTimeMillis() / 1000,
                        message = taskSync.appendNotes(receipt.message),
                    ),
                )
                accountPreferenceWriter.updatePreferredSeat(
                    studentId = selection.studentId,
                    preferredRoomName = task.roomName,
                    preferredSeatNumber = task.seatNumber,
                )
                guardScheduler.schedule(
                    taskId = taskId,
                    startTimeEpochSeconds = task.startTimeEpochSeconds,
                    limitSignAgoSeconds = task.limitSignAgoSeconds,
                )
                ManualReservationResult.Success(
                    taskId = task.id,
                    bookingId = receipt.bookingId,
                    message = receipt.message,
                )
            }.getOrElse { error ->
                ManualReservationResult.Failure(
                    error.message?.takeIf(String::isNotBlank) ?: "预约失败，请稍后重试。",
                )
            }
        }

    private fun loadTaskSync(
        session: AuthenticatedSession,
        bookingId: String,
    ): ManualReservationTaskSync =
        runCatching {
            bookingDetailLoader.load(session, bookingId)
        }.getOrNull()
            ?.toTaskSync()
            ?: ManualReservationTaskSync(
                expectedMinorsCsv = "",
                limitSignAgoSeconds = CheckInWindow.FALLBACK_SECONDS,
                limitSignBackSeconds = CheckInWindow.FALLBACK_SECONDS,
                notes = listOf("读取预约详情失败", "接口未返回有效蓝牙设备信息", "使用兜底签到窗口"),
            )

    private fun BookingDetail.toTaskSync(): ManualReservationTaskSync {
        val expectedMinorsCsv = expectedMinors.toMinorCsv()
        val notes = buildList {
            if (expectedMinorsCsv.isBlank()) {
                add("接口未返回有效蓝牙设备信息")
            }
            if (window.limitSignAgoSeconds == CheckInWindow.FALLBACK_SECONDS ||
                window.limitSignBackSeconds == CheckInWindow.FALLBACK_SECONDS
            ) {
                add("使用兜底签到窗口")
            }
        }
        return ManualReservationTaskSync(
            expectedMinorsCsv = expectedMinorsCsv,
            limitSignAgoSeconds = window.limitSignAgoSeconds,
            // 与周期签到一致，把 limitSignBackSeconds 截断到 30 分钟，避免签到截止时间过长。
            limitSignBackSeconds = CheckInWindow.capSignBackSeconds(window.limitSignBackSeconds),
            notes = notes,
        )
    }

    private fun resolveLookupForReservation(
        selection: ManualReservationSelection,
        session: com.wuyi.libraryauto.core.network.auth.SessionBundle,
    ): ResolvedReservationLookup {
        var lastError: Throwable? = null
        buildEntryUrlCandidates(selection.entryUrl, entryUrls).forEach { entryUrl ->
            val resolvedSeatUrlStore = resolvedSeatUrlRepository
            val cachedSearchApiUrl =
                resolvedSeatUrlStore
                    ?.load(studentId = selection.studentId, entryUrl = entryUrl)
                    ?.takeIf(String::isNotBlank)
            if (cachedSearchApiUrl != null) {
                val cachedAttempt =
                    runCatching {
                        loadLookupFromSearchApiUrl(
                            searchApiUrl = cachedSearchApiUrl,
                            selection = selection,
                            session = session,
                        )
                    }
                val resolvedLookup = cachedAttempt.getOrNull()
                if (resolvedLookup != null) {
                    return resolvedLookup
                }
                resolvedSeatUrlStore.remove(studentId = selection.studentId, entryUrl = entryUrl)
                lastError = cachedAttempt.exceptionOrNull()
            }
            val searchApiUrls =
                seatQueryGateway.resolveSearchApiUrls(
                    entryUrl = entryUrl,
                    session = session,
                )
            for (searchApiUrl in searchApiUrls) {
                if (searchApiUrl == cachedSearchApiUrl) {
                    continue
                }
                val attempt =
                    runCatching {
                        loadLookupFromSearchApiUrl(
                            searchApiUrl = searchApiUrl,
                            selection = selection,
                            session = session,
                        )
                    }
                val resolvedLookup = attempt.getOrNull()
                if (resolvedLookup != null) {
                    resolvedSeatUrlRepository?.save(
                        studentId = selection.studentId,
                        entryUrl = entryUrl,
                        searchApiUrl = searchApiUrl,
                    )
                    return resolvedLookup
                }
                lastError = attempt.exceptionOrNull()
            }
        }
        throw lastError ?: IllegalArgumentException("未能从入口页解析 searchSeats 接口地址，请检查 seat_urls 配置")
    }

    private fun loadLookupFromSearchApiUrl(
        searchApiUrl: String,
        selection: ManualReservationSelection,
        session: com.wuyi.libraryauto.core.network.auth.SessionBundle,
    ): ResolvedReservationLookup {
        val searchPage =
            seatQueryGateway.fetchSearchPage(
                searchApiUrl = searchApiUrl,
                session = session,
            )
        val lookupResult =
            seatQueryGateway.searchSeats(
                searchApiUrl = searchApiUrl,
                session = session,
                formFields =
                    CoreSeatLookupRepository.buildCustomSearchFormPayload(
                        context = searchPage,
                        beginTime = selection.beginTimeEpochSeconds,
                        durationSeconds = selection.durationSeconds,
                        peopleCount = selection.peopleCount,
                    ),
            )
        return ResolvedReservationLookup(
            searchApiUrl = searchApiUrl,
            lookupResult = lookupResult,
        )
    }

    private data class ResolvedReservationLookup(
        val searchApiUrl: String,
        val lookupResult: SeatLookupResult,
    )

    private data class ManualReservationTaskSync(
        val expectedMinorsCsv: String,
        val limitSignAgoSeconds: Long,
        val limitSignBackSeconds: Long,
        val notes: List<String>,
    ) {
        fun appendNotes(message: String): String =
            if (notes.isEmpty()) {
                message
            } else {
                "$message · ${notes.joinToString("，")}"
            }
    }

    private fun List<Int>.toMinorCsv(): String =
        asSequence()
            .filter { minor -> minor in 0..65_535 }
            .distinct()
            .sorted()
            .take(MAX_EXPECTED_MINORS)
            .joinToString(",")

    private companion object {
        private const val MAX_EXPECTED_MINORS = 256
    }
}

private class SeatStatusManualBookingDetailLoader : ManualBookingDetailLoader {
    override fun load(
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

    private fun buildSeatServiceOrigins(session: AuthenticatedSession): List<String> =
        listOf(session.origin, SchoolPortalConfig.SeatServiceOrigin)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()

    private fun buildBookingListUrl(origin: String): String =
        SchoolSeatApi.appendLabJson("${origin.removeSuffix("/")}${SchoolSeatApi.MY_BOOKING_LIST_PATH}")
}
