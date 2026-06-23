package com.wuyi.libraryauto.ui.repository.seat

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.domain.model.CheckInWindow
import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.core.network.auth.SessionBundle
import com.wuyi.libraryauto.core.network.seat.BookingDetail
import com.wuyi.libraryauto.core.network.seat.ReservationReceipt
import com.wuyi.libraryauto.core.network.seat.SearchPageContext
import com.wuyi.libraryauto.core.network.seat.SeatLookupResult
import com.wuyi.libraryauto.core.network.seat.SeatMapSnapshot
import com.wuyi.libraryauto.core.network.seat.SeatPoi
import com.wuyi.libraryauto.core.network.seat.SeatQueryGateway
import com.wuyi.libraryauto.core.network.seat.SeatReservationGateway
import com.wuyi.libraryauto.core.storage.db.ReservationTaskDao
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import com.wuyi.libraryauto.ui.repository.session.SessionRepository
import com.wuyi.libraryauto.ui.repository.task.AccountPreferenceWriter
import com.wuyi.libraryauto.ui.repository.task.PreferredSeatUpdate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManualReservationRepositoryTest {

    @Test
    fun `reserve validates seat submits booking and persists guard task`() = runTest {
        val reservationTaskDao = FakeReservationTaskDao()
        val accountPreferenceWriter = FakeAccountPreferenceWriter()
        val guardScheduler = FakeReservationGuardScheduler()
        val seatQueryGateway = FakeSeatQueryGateway()
        val reservationGateway =
            FakeSeatReservationGateway(
                ReservationReceipt(
                    bookingId = "booking-166",
                    message = "已提交 自习室圆形二楼 166 号座位预约",
                ),
            )
        val repository =
            ManualReservationRepository(
                seatQueryGateway = seatQueryGateway,
                reservationGateway = reservationGateway,
                reservationTaskDao = reservationTaskDao,
                accountPreferenceWriter = accountPreferenceWriter,
                sessionRepository = FakeSessionRepository(loggedInSession()),
                guardScheduler = guardScheduler,
                bookingDetailLoader = FakeManualBookingDetailLoader(),
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )

        val result =
            repository.reserve(
                ManualReservationSelection(
                    studentId = "20230001",
                    entryUrl = "https://example.com/#!/Space/Category/list",
                    roomId = "room-2f",
                    roomName = "自习室圆形二楼",
                    seatNumber = "166",
                    beginTimeEpochSeconds = 1_712_800_000,
                    durationSeconds = 14_400,
                ),
            )

        assertThat(result).isEqualTo(
            ManualReservationResult.Success(
                taskId = "20230001:booking-166",
                bookingId = "booking-166",
                message = "已提交 自习室圆形二楼 166 号座位预约",
            ),
        )
        assertThat(seatQueryGateway.lastEntryUrl).isEqualTo("https://example.com/#!/Space/Category/list")
        assertThat(seatQueryGateway.lastFormFields).containsExactly(
            "beginTime" to "1712800000",
            "duration" to "14400",
            "num" to "1",
            "space_category[category_id]" to "11",
            "space_category[content_id]" to "301",
        ).inOrder()
        assertThat(reservationGateway.lastReservation).isEqualTo(
            ReservationCall(
                searchApiUrl = "https://example.com/Seat/Index/searchSeats?content_id=301",
                seatId = "seat-166",
                roomId = "room-2f",
                roomName = "自习室圆形二楼",
                seatNumber = "166",
                beginTime = 1_712_800_000,
                durationSeconds = 14_400,
            ),
        )
        assertThat(reservationTaskDao.lastUpserted).isEqualTo(
            ReservationTaskEntity(
                id = "20230001:booking-166",
                studentId = "20230001",
                roomName = "自习室圆形二楼",
                seatNumber = "166",
                state = ReservationTaskState.RESERVED_WAITING_SIGNIN,
                bookingId = "booking-166",
                startTimeEpochSeconds = 1_712_800_000L,
                limitSignAgoSeconds = 600L,
                // 与生产口径一致：limitSignBackSeconds 在写库前会被截到 30 分钟（1800s）。
                limitSignBackSeconds = 1_800L,
                expectedMinorsCsv = "12,16",
                lastError = null,
            ),
        )
        assertThat(accountPreferenceWriter.lastUpdate).isEqualTo(
            PreferredSeatUpdate(
                studentId = "20230001",
                preferredRoomName = "自习室圆形二楼",
                preferredSeatNumber = "166",
            ),
        )
        assertThat(guardScheduler.lastScheduled).isEqualTo(
            GuardSchedule(
                taskId = "20230001:booking-166",
                startTimeEpochSeconds = 1_712_800_000L,
                limitSignAgoSeconds = 600L,
            ),
        )
    }

    @Test
    fun `reserve retries next candidate search url when first one is invalid`() = runTest {
        val reservationGateway =
            FakeSeatReservationGateway(
                ReservationReceipt(
                    bookingId = "booking-166",
                    message = "已提交 自习室圆形二楼 166 号座位预约",
                ),
            )
        val seatQueryGateway =
            FakeSeatQueryGateway().apply {
                candidateSearchApiUrls =
                    listOf(
                        "https://example.com/Seat/Index/searchSeats?content_id=999",
                        "https://example.com/Seat/Index/searchSeats?content_id=301",
                    )
                failingSearchApiUrls += "https://example.com/Seat/Index/searchSeats?content_id=999"
            }
        val repository =
            ManualReservationRepository(
                seatQueryGateway = seatQueryGateway,
                reservationGateway = reservationGateway,
                reservationTaskDao = FakeReservationTaskDao(),
                accountPreferenceWriter = FakeAccountPreferenceWriter(),
                sessionRepository = FakeSessionRepository(loggedInSession()),
                guardScheduler = FakeReservationGuardScheduler(),
                bookingDetailLoader = FakeManualBookingDetailLoader(),
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )

        val result =
            repository.reserve(
                ManualReservationSelection(
                    studentId = "20230001",
                    entryUrl = "https://example.com/#!/Space/Category/list",
                    roomId = "room-2f",
                    roomName = "自习室圆形二楼",
                    seatNumber = "166",
                    beginTimeEpochSeconds = 1_712_800_000,
                    durationSeconds = 14_400,
                ),
            )

        assertThat(result).isInstanceOf(ManualReservationResult.Success::class.java)
        assertThat(reservationGateway.lastReservation?.searchApiUrl)
            .isEqualTo("https://example.com/Seat/Index/searchSeats?content_id=301")
    }

    @Test
    fun `reserve prefers cached resolved search url for matching student and entry`() = runTest {
        val reservationGateway =
            FakeSeatReservationGateway(
                ReservationReceipt(
                    bookingId = "booking-166",
                    message = "已提交 自习室圆形二楼 166 号座位预约",
                ),
            )
        val entryUrl = "https://example.com/#!/Space/Category/list"
        val resolvedSearchApiUrl =
            "https://example.com/Seat/Index/searchSeats?" +
                "space_category%5Bcategory_id%5D=591&space_category%5Bcontent_id%5D=28"
        val resolvedSeatUrlRepository =
            InMemoryResolvedSeatUrlRepository().apply {
                save(studentId = "20230001", entryUrl = entryUrl, searchApiUrl = resolvedSearchApiUrl)
            }
        val seatQueryGateway =
            FakeSeatQueryGateway()
        val repository =
            ManualReservationRepository(
                seatQueryGateway = seatQueryGateway,
                reservationGateway = reservationGateway,
                reservationTaskDao = FakeReservationTaskDao(),
                accountPreferenceWriter = FakeAccountPreferenceWriter(),
                sessionRepository = FakeSessionRepository(loggedInSession()),
                guardScheduler = FakeReservationGuardScheduler(),
                resolvedSeatUrlRepository = resolvedSeatUrlRepository,
                bookingDetailLoader = FakeManualBookingDetailLoader(),
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )

        val result =
            repository.reserve(
                ManualReservationSelection(
                    studentId = "20230001",
                    entryUrl = entryUrl,
                    roomId = "room-2f",
                    roomName = "自习室圆形二楼",
                    seatNumber = "166",
                    beginTimeEpochSeconds = 1_712_800_000,
                    durationSeconds = 14_400,
                ),
            )

        assertThat(result).isInstanceOf(ManualReservationResult.Success::class.java)
        assertThat(reservationGateway.lastReservation?.searchApiUrl).isEqualTo(resolvedSearchApiUrl)
        assertThat(seatQueryGateway.resolvedEntryUrls).isEmpty()
    }

    private fun loggedInSession(): AuthenticatedSession =
        AuthenticatedSession(
            session = SessionBundle(cookieHeader = "auth=token", userId = "405963"),
            cookies = emptyList(),
            currentUserJson = """{"id":"405963"}""",
            origin = "https://example.com",
            installationId = "install-1",
        )

    private data class ReservationCall(
        val searchApiUrl: String,
        val seatId: String,
        val roomId: String,
        val roomName: String,
        val seatNumber: String,
        val beginTime: Int,
        val durationSeconds: Int,
    )

    private data class GuardSchedule(
        val taskId: String,
        val startTimeEpochSeconds: Long,
        val limitSignAgoSeconds: Long,
    )

    private class FakeSeatQueryGateway : SeatQueryGateway {
        var lastEntryUrl: String? = null
        var lastFormFields: List<Pair<String, String>> = emptyList()
        var candidateSearchApiUrls: List<String> = listOf("https://example.com/Seat/Index/searchSeats?content_id=301")
        val candidateSearchApiUrlsByEntryUrl = linkedMapOf<String, List<String>>()
        val failingSearchApiUrls = mutableSetOf<String>()
        val resolvedEntryUrls = mutableListOf<String>()

        override fun resolveSearchApiUrl(
            entryUrl: String,
            session: SessionBundle,
        ): String {
            lastEntryUrl = entryUrl
            return candidateSearchApiUrls.first()
        }

        override fun resolveSearchApiUrls(
            entryUrl: String,
            session: SessionBundle,
        ): List<String> {
            lastEntryUrl = entryUrl
            resolvedEntryUrls += entryUrl
            return candidateSearchApiUrlsByEntryUrl[entryUrl] ?: candidateSearchApiUrls
        }

        override fun fetchSearchPage(
            searchApiUrl: String,
            session: SessionBundle,
        ): SearchPageContext {
            if (searchApiUrl in failingSearchApiUrls) {
                throw IllegalArgumentException("查询页没有返回 data，当前接口返回：com.Message，CODE=所属空间不存在")
            }
            return SearchPageContext(
                searchApiUrl = searchApiUrl,
                rawPayload = "",
                defaultBeginTime = 1_712_700_000,
                defaultDurationHours = 4,
                defaultPeopleCount = 1,
                categoryId = "11",
                contentId = "301",
            )
        }

        override fun searchSeats(
            searchApiUrl: String,
            session: SessionBundle,
            formFields: List<Pair<String, String>>,
        ): SeatLookupResult {
            lastFormFields = formFields
            return SeatLookupResult(
                rawPayload = "",
                roomMaps =
                    listOf(
                        SeatMapSnapshot(
                            roomId = "room-2f",
                            roomName = "自习室圆形二楼",
                            storey = "2F",
                            planUrl = "",
                            width = 1000,
                            height = 600,
                            availableCount = 1,
                            lockedCount = 0,
                            selectedSeatId = null,
                            selectedSeatNumber = null,
                            systemRecommendedSeatId = null,
                            systemRecommendedSeatNumber = null,
                            seats =
                                listOf(
                                    SeatPoi(
                                        seatId = "seat-166",
                                        seatNumber = "166",
                                        x = 0,
                                        y = 0,
                                        w = 10,
                                        h = 10,
                                        state = "0",
                                        selectable = true,
                                        recommended = false,
                                    ),
                                ),
                        ),
                    ),
                selectedRoom =
                    SeatMapSnapshot(
                        roomId = "room-2f",
                        roomName = "自习室圆形二楼",
                        storey = "2F",
                        planUrl = "",
                        width = 1000,
                        height = 600,
                        availableCount = 1,
                        lockedCount = 0,
                        selectedSeatId = null,
                        selectedSeatNumber = null,
                        systemRecommendedSeatId = null,
                        systemRecommendedSeatNumber = null,
                        seats = emptyList(),
                    ),
            )
        }
    }

    private class InMemoryResolvedSeatUrlRepository : ResolvedSeatUrlRepository {
        private val values = linkedMapOf<Pair<String, String>, String>()

        override fun load(
            studentId: String,
            entryUrl: String,
        ): String? = values[studentId.trim() to entryUrl.trim()]

        override fun save(
            studentId: String,
            entryUrl: String,
            searchApiUrl: String,
        ) {
            values[studentId.trim() to entryUrl.trim()] = searchApiUrl.trim()
        }

        override fun remove(
            studentId: String,
            entryUrl: String,
        ) {
            values.remove(studentId.trim() to entryUrl.trim())
        }
    }

    private class FakeSeatReservationGateway(
        private val receipt: ReservationReceipt,
    ) : SeatReservationGateway {
        var lastReservation: ReservationCall? = null

        override fun reserveSeat(
            searchApiUrl: String,
            session: SessionBundle,
            seatId: String,
            roomId: String,
            roomName: String,
            seatNumber: String,
            beginTime: Int,
            durationSeconds: Int,
        ): ReservationReceipt {
            lastReservation =
                ReservationCall(
                    searchApiUrl = searchApiUrl,
                    seatId = seatId,
                    roomId = roomId,
                    roomName = roomName,
                    seatNumber = seatNumber,
                    beginTime = beginTime,
                    durationSeconds = durationSeconds,
                )
            return receipt
        }
    }

    private class FakeReservationTaskDao : ReservationTaskDao {
        var lastUpserted: ReservationTaskEntity? = null

        override suspend fun upsert(task: ReservationTaskEntity) {
            lastUpserted = task
        }

        override suspend fun findById(id: String): ReservationTaskEntity? = null

        override suspend fun findLatestForStudent(studentId: String): ReservationTaskEntity? = null

        override suspend fun listForStudent(studentId: String): List<ReservationTaskEntity> = emptyList()

        override fun observeAll(): Flow<List<ReservationTaskEntity>> = MutableStateFlow(emptyList())

        override suspend fun listAll(): List<ReservationTaskEntity> = emptyList()
    }

    private class FakeAccountPreferenceWriter : AccountPreferenceWriter {
        var lastUpdate: PreferredSeatUpdate? = null

        override fun updatePreferredSeat(
            studentId: String,
            preferredRoomName: String,
            preferredSeatNumber: String,
        ) {
            lastUpdate =
                PreferredSeatUpdate(
                    studentId = studentId,
                    preferredRoomName = preferredRoomName,
                    preferredSeatNumber = preferredSeatNumber,
                )
        }
    }

    private class FakeReservationGuardScheduler : ReservationGuardScheduler {
        var lastScheduled: GuardSchedule? = null

        override fun schedule(
            taskId: String,
            startTimeEpochSeconds: Long,
            limitSignAgoSeconds: Long,
        ) {
            lastScheduled =
                GuardSchedule(
                    taskId = taskId,
                    startTimeEpochSeconds = startTimeEpochSeconds,
                    limitSignAgoSeconds = limitSignAgoSeconds,
                )
        }
    }

    private class FakeManualBookingDetailLoader : ManualBookingDetailLoader {
        override fun load(
            session: AuthenticatedSession,
            bookingId: String,
        ): BookingDetail =
            BookingDetail(
                bookingId = bookingId,
                window =
                    CheckInWindow(
                        startEpochSeconds = 1_712_800_000L,
                        limitSignAgoSeconds = 600L,
                        limitSignBackSeconds = 2_400L,
                    ),
                expectedMinors = listOf(16, 12, 12),
                statusLabel = "待签到",
                isAlreadySignedIn = false,
            )
    }

    private class FakeSessionRepository(
        initialSession: AuthenticatedSession?,
    ) : SessionRepository {
        private val state = MutableStateFlow(initialSession)
        private val sessions =
            linkedMapOf<String, AuthenticatedSession>().apply {
                if (initialSession != null) {
                    put("20230001", initialSession)
                }
            }
        private var activeStudentId: String? = "20230001"

        override val session: StateFlow<AuthenticatedSession?> = state.asStateFlow()

        override fun currentSession(): AuthenticatedSession? = state.value

        override fun currentSession(studentId: String): AuthenticatedSession? = sessions[studentId]

        override fun activeStudentId(): String? = activeStudentId

        override fun activate(studentId: String): Boolean = sessions.containsKey(studentId)

        override fun save(session: AuthenticatedSession) = Unit

        override fun save(
            studentId: String,
            session: AuthenticatedSession,
            activate: Boolean,
        ) = Unit

        override fun remove(studentId: String) = Unit

        override fun clear() = Unit
    }
}
