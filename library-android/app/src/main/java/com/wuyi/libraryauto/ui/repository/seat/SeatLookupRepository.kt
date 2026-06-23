package com.wuyi.libraryauto.ui.repository.seat

import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.core.network.auth.SessionBundle
import com.wuyi.libraryauto.core.network.seat.SeatLookupResult
import com.wuyi.libraryauto.core.network.seat.SeatMapSnapshot
import com.wuyi.libraryauto.core.network.seat.SeatLookupService
import com.wuyi.libraryauto.ui.repository.SchoolPortalConfig
import com.wuyi.libraryauto.ui.repository.settings.SeatLookupAuditRepository
import com.wuyi.libraryauto.ui.repository.session.WebSessionBootstrapper
import com.wuyi.libraryauto.ui.repository.session.SessionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import com.wuyi.libraryauto.core.network.seat.SeatLookupRepository as CoreSeatLookupRepository

interface SeatLookupRepository {
    suspend fun loadDefaultSeats(): SeatLookupLoadResult

    suspend fun loadDefaultSeats(studentId: String): SeatLookupLoadResult = loadDefaultSeats()

    suspend fun loadSeats(query: SeatLookupQuery): SeatLookupLoadResult =
        loadDefaultSeats(query.studentId)
}

class NetworkSeatLookupRepository(
    private val seatLookupService: SeatLookupService,
    private val sessionRepository: SessionRepository,
    private val entryUrls: List<String> = SchoolPortalConfig.SeatEntryUrls,
    private val resolvedSeatUrlRepository: ResolvedSeatUrlRepository? = null,
    private val seatLookupAuditRepository: SeatLookupAuditRepository? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SeatLookupRepository {

    override suspend fun loadDefaultSeats(): SeatLookupLoadResult =
        withContext(ioDispatcher) {
            val activeStudentId = sessionRepository.activeStudentId().orEmpty().trim()
            val authenticatedSession = awaitActiveSession()
                ?: return@withContext SeatLookupLoadResult.NotLoggedIn
            loadDefaultSeats(
                authenticatedSession = authenticatedSession,
                studentId = activeStudentId,
            )
        }

    override suspend fun loadDefaultSeats(studentId: String): SeatLookupLoadResult =
        withContext(ioDispatcher) {
            val authenticatedSession = awaitSession(studentId)
                ?: return@withContext SeatLookupLoadResult.NotLoggedIn
            loadDefaultSeats(
                authenticatedSession = authenticatedSession,
                studentId = studentId,
            )
        }

    override suspend fun loadSeats(query: SeatLookupQuery): SeatLookupLoadResult =
        withContext(ioDispatcher) {
            val authenticatedSession = awaitSession(query.studentId)
                ?: return@withContext SeatLookupLoadResult.NotLoggedIn.also {
                    seatLookupAuditRepository?.recordFailure(
                        studentId = query.studentId,
                        entryUrl = query.entryUrl,
                        message = "当前账号未认证，请先去账号列表刷新认证。",
                    )
                }
            runCatching {
                val data =
                    loadLookupData(
                        studentId = query.studentId,
                        entryUrls = buildEntryUrlCandidates(query.entryUrl, entryUrls),
                        session = authenticatedSession.session,
                        beginTimeEpochSeconds = query.beginTimeEpochSeconds,
                        durationSeconds = query.durationSeconds,
                        peopleCount = query.peopleCount,
                    )
                if (data.rooms.isEmpty()) {
                    SeatLookupLoadResult.Empty(data)
                } else {
                    SeatLookupLoadResult.Success(data)
                }
            }.onSuccess { result ->
                val summary =
                    when (result) {
                        is SeatLookupLoadResult.Success -> "已返回 ${result.data.rooms.size} 个房间。"
                        is SeatLookupLoadResult.Empty -> result.data.notice ?: "当前条件下没有可用座位。"
                        else -> ""
                    }
                if (summary.isNotBlank()) {
                    seatLookupAuditRepository?.recordSuccess(
                        studentId = query.studentId,
                        entryUrl = query.entryUrl,
                        message = summary,
                    )
                }
            }.getOrElse { error ->
                val message = error.toSeatLookupMessage()
                seatLookupAuditRepository?.recordFailure(
                    studentId = query.studentId,
                    entryUrl = query.entryUrl,
                    message = message,
                )
                SeatLookupLoadResult.Failure(message)
            }
        }

    private suspend fun awaitActiveSession(): AuthenticatedSession? {
        val activeStudentId = sessionRepository.activeStudentId().orEmpty().trim()
        if (activeStudentId.isBlank()) {
            return sessionRepository.currentSession()
        }
        return awaitSession(activeStudentId)
    }

    private suspend fun awaitSession(studentId: String): AuthenticatedSession? {
        val safeStudentId = studentId.trim()
        if (safeStudentId.isBlank()) {
            return null
        }
        val bootstrapper = WebSessionBootstrapper(sessionRepository)
        repeat(MAX_WEB_SESSION_WAIT_COUNT) {
            val session = sessionRepository.currentSession(safeStudentId) ?: return null
            if (!bootstrapper.needsBootstrap(session)) {
                return session
            }
            delay(WEB_SESSION_WAIT_MILLIS)
        }
        return sessionRepository.currentSession(safeStudentId)
    }

    private fun loadDefaultSeats(
        authenticatedSession: AuthenticatedSession,
        studentId: String,
    ): SeatLookupLoadResult {
        val errorOrData =
            runCatching {
                loadLookupData(
                    studentId = studentId,
                    entryUrls = entryUrls,
                    session = authenticatedSession.session,
                )
            }
        val data = errorOrData.getOrNull()
        if (data != null) {
            return if (data.rooms.isEmpty()) {
                SeatLookupLoadResult.Empty(data)
            } else {
                SeatLookupLoadResult.Success(data)
            }
        }

        val error = errorOrData.exceptionOrNull() ?: IllegalArgumentException("未配置可用的查座位入口")
        return error.toCatalogNotice()
            ?.let { notice -> SeatLookupLoadResult.Success(DefaultSeatCatalog.buildLookupData(notice)) }
            ?: SeatLookupLoadResult.Failure(error.toSeatLookupMessage())
    }

    private fun loadLookupData(
        studentId: String,
        entryUrls: List<String>,
        session: SessionBundle,
        beginTimeEpochSeconds: Int? = null,
        durationSeconds: Int? = null,
        peopleCount: Int? = null,
    ): SeatLookupData {
        var lastError: Throwable? = null
        entryUrls.forEach { entryUrl ->
            val resolvedSeatUrlStore = resolvedSeatUrlRepository
            val cachedSearchApiUrl =
                resolvedSeatUrlStore
                    ?.load(studentId = studentId, entryUrl = entryUrl)
                    ?.takeIf(String::isNotBlank)
            if (cachedSearchApiUrl != null) {
                val cachedAttempt =
                    runCatching {
                        loadLookupDataFromSearchApiUrl(
                            searchApiUrl = cachedSearchApiUrl,
                            session = session,
                            beginTimeEpochSeconds = beginTimeEpochSeconds,
                            durationSeconds = durationSeconds,
                            peopleCount = peopleCount,
                        )
                    }
                val cachedData = cachedAttempt.getOrNull()
                if (cachedData != null) {
                    return cachedData
                }
                resolvedSeatUrlStore.remove(studentId = studentId, entryUrl = entryUrl)
                lastError = cachedAttempt.exceptionOrNull()
            }
            val searchApiUrls =
                seatLookupService.resolveSearchApiUrls(
                    entryUrl = entryUrl,
                    session = session,
                )
            for (searchApiUrl in searchApiUrls) {
                if (searchApiUrl == cachedSearchApiUrl) {
                    continue
                }
                val attempt =
                    runCatching {
                        loadLookupDataFromSearchApiUrl(
                            searchApiUrl = searchApiUrl,
                            session = session,
                            beginTimeEpochSeconds = beginTimeEpochSeconds,
                            durationSeconds = durationSeconds,
                            peopleCount = peopleCount,
                        )
                    }
                val data = attempt.getOrNull()
                if (data != null) {
                    resolvedSeatUrlRepository?.save(
                        studentId = studentId,
                        entryUrl = entryUrl,
                        searchApiUrl = searchApiUrl,
                    )
                    return data
                }
                lastError = attempt.exceptionOrNull()
            }
        }
        throw lastError ?: IllegalArgumentException("未能从入口页解析 searchSeats 接口地址，请检查 seat_urls 配置")
    }

    private fun loadLookupDataFromSearchApiUrl(
        searchApiUrl: String,
        session: SessionBundle,
        beginTimeEpochSeconds: Int? = null,
        durationSeconds: Int? = null,
        peopleCount: Int? = null,
    ): SeatLookupData {
        val searchPage =
            seatLookupService.fetchSearchPage(
                searchApiUrl = searchApiUrl,
                session = session,
            )
        val lookupResult =
            seatLookupService.searchSeats(
                searchApiUrl = searchApiUrl,
                session = session,
                formFields =
                    if (
                        beginTimeEpochSeconds != null &&
                        durationSeconds != null &&
                        peopleCount != null
                    ) {
                        CoreSeatLookupRepository.buildCustomSearchFormPayload(
                            context = searchPage,
                            beginTime = beginTimeEpochSeconds,
                            durationSeconds = durationSeconds,
                            peopleCount = peopleCount,
                        )
                    } else {
                        CoreSeatLookupRepository.buildSearchFormPayload(searchPage)
                    },
            )
        return SeatLookupData(
            beginTimeEpochSeconds = beginTimeEpochSeconds ?: searchPage.defaultBeginTime,
            durationHours =
                durationSeconds?.div(3600)?.takeIf { it > 0 } ?: searchPage.defaultDurationHours,
            peopleCount = peopleCount ?: searchPage.defaultPeopleCount,
            rooms = lookupResult.toRoomSnapshots(),
        )
    }

    private fun SeatLookupResult.toRoomSnapshots(): List<SeatRoomSnapshot> =
        roomMaps
            .ifEmpty { listOf(selectedRoom) }
            .filter { snapshot -> snapshot.roomId.isNotBlank() }
            .map { snapshot -> snapshot.toRoomSnapshot() }

    private fun SeatMapSnapshot.toRoomSnapshot(): SeatRoomSnapshot {
        val availableSeats =
            seats
                .asSequence()
                .filter { seat -> seat.selectable }
                .map { seat -> seat.seatNumber.ifBlank { seat.seatId } }
                .filter(String::isNotBlank)
                .toList()

        return SeatRoomSnapshot(
            roomId = roomId,
            roomName = roomName.ifBlank { "未命名房间" },
            storey = storey,
            availableCount = availableCount.takeIf { it >= 0 } ?: availableSeats.size,
            seatNumbers = availableSeats,
            recommendedSeatNumber =
                systemRecommendedSeatNumber
                    ?.takeIf(String::isNotBlank)
                    ?: seats.firstOrNull { seat -> seat.recommended }?.seatNumber?.takeIf(String::isNotBlank),
        )
    }
}

private fun Throwable.toCatalogNotice(): String? {
    if (this !is IllegalArgumentException) {
        return null
    }

    val detail = message?.takeIf(String::isNotBlank)
    return if (detail == null) {
        "学校接口当前没有返回标准查座位数据，先展示默认房间目录。真正预约时仍会再向学校接口取实时结果。"
    } else {
        "学校接口当前没有返回标准查座位数据（$detail），先展示默认房间目录。真正预约时仍会再向学校接口取实时结果。"
    }
}

private fun Throwable.toSeatLookupMessage(): String =
    when (this) {
        is IOException -> "查询座位失败，请检查网络后重试。"
        is IllegalArgumentException -> message?.takeIf(String::isNotBlank) ?: "查询座位失败，请稍后重试。"
        else -> message?.takeIf(String::isNotBlank) ?: "查询座位失败，请稍后重试。"
    }

private const val MAX_WEB_SESSION_WAIT_COUNT = 16
private const val WEB_SESSION_WAIT_MILLIS = 750L
