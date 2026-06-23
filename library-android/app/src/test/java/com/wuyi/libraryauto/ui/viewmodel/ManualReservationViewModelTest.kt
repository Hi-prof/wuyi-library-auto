package com.wuyi.libraryauto.ui.viewmodel

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.core.network.auth.SessionBundle
import com.wuyi.libraryauto.ui.repository.seat.ManualReservationGateway
import com.wuyi.libraryauto.ui.repository.seat.ManualReservationResult
import com.wuyi.libraryauto.ui.repository.seat.ManualReservationSelection
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupLoadResult
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupQuery
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupRepository
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupData
import com.wuyi.libraryauto.ui.repository.seat.SeatRoomSnapshot
import com.wuyi.libraryauto.ui.repository.session.SessionRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import com.wuyi.libraryauto.ui.repository.SchoolPortalConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManualReservationViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `init defaults to current account and current hour`() {
        val viewModel = buildViewModel(activeStudentId = "20230002")

        assertThat(viewModel.uiState.selectedStudentId).isEqualTo("20230002")
        assertThat(viewModel.uiState.accounts.first { it.studentId == "20230002" }.isAuthenticated).isTrue()
        assertThat(viewModel.uiState.selectedDate).isEqualTo("2026-04-11")
        assertThat(viewModel.uiState.selectedStartTime).isEqualTo("09:00")
        assertThat(viewModel.uiState.durationHours).isEqualTo("4")
    }

    @Test
    fun `query failure keeps selected seat until refresh succeeds`() = runTest {
        val seatLookupRepository = FakeSeatLookupRepository()
        val viewModel = buildViewModel(seatLookupRepository = seatLookupRepository)

        viewModel.selectSeat(
            roomId = "room-2f",
            roomName = "自习室圆形二楼",
            seatNumber = "166",
        )
        seatLookupRepository.nextQueryResult = SeatLookupLoadResult.Failure("查询座位失败，请稍后重试。")

        viewModel.querySeats()
        advanceUntilIdle()

        assertThat(viewModel.uiState.selectedSeatNumber).isEqualTo("166")
        assertThat(viewModel.uiState.statusMessage).isEqualTo("查询座位失败，请稍后重试。")
        assertThat(seatLookupRepository.lastQuery).isEqualTo(
            SeatLookupQuery(
                studentId = "20230001",
                entryUrl = SchoolPortalConfig.LoginUrl,
                beginTimeEpochSeconds = expectedEpoch("2026-04-11T09:00:00"),
                durationSeconds = 14_400,
                peopleCount = 1,
            ),
        )
    }

    @Test
    fun `reserveSelectedSeat submits current query window`() = runTest {
        val manualReservationRepository = FakeManualReservationRepository()
        val viewModel =
            buildViewModel(
                manualReservationRepository = manualReservationRepository,
            )

        viewModel.updateSelectedDate("2026-04-12")
        viewModel.updateSelectedStartTime("08:00")
        viewModel.updateDurationHours("4")
        viewModel.selectSeat(
            roomId = "room-2f",
            roomName = "自习室圆形二楼",
            seatNumber = "166",
        )

        viewModel.reserveSelectedSeat()
        advanceUntilIdle()

        assertThat(manualReservationRepository.lastSelection).isEqualTo(
            ManualReservationSelection(
                studentId = "20230001",
                entryUrl = SchoolPortalConfig.LoginUrl,
                roomId = "room-2f",
                roomName = "自习室圆形二楼",
                seatNumber = "166",
                beginTimeEpochSeconds = expectedEpoch("2026-04-12T08:00:00"),
                durationSeconds = 14_400,
            ),
        )
        assertThat(viewModel.uiState.statusMessage).isEqualTo("已提交 自习室圆形二楼 166 号座位预约")
    }

    private fun buildViewModel(
        activeStudentId: String = "20230001",
        seatLookupRepository: FakeSeatLookupRepository = FakeSeatLookupRepository(),
        manualReservationRepository: FakeManualReservationRepository = FakeManualReservationRepository(),
    ): ManualReservationViewModel =
        ManualReservationViewModel(
            accountRepository =
                FakeSavedAccountRepository(
                    entries =
                        listOf(
                            SavedAccountEntry(
                                studentId = "20230001",
                                password = "alpha",
                                preferredRoomName = "自习室圆形二楼",
                                preferredSeatNumber = "166",
                            ),
                            SavedAccountEntry(studentId = "20230002", password = "beta"),
                        ),
                ),
            seatLookupRepository = seatLookupRepository,
            manualReservationRepository = manualReservationRepository,
            sessionRepository = FakeSessionRepository(activeStudentId = activeStudentId),
            clock = Clock.fixed(Instant.parse("2026-04-11T01:30:00Z"), zoneId),
            zoneId = zoneId,
        )

    private class FakeSavedAccountRepository(
        private val entries: List<SavedAccountEntry>,
    ) : SavedAccountRepository {
        override fun readAll(): List<SavedAccountEntry> = entries

        override fun remove(studentId: String) = Unit
    }

    private class FakeSeatLookupRepository : SeatLookupRepository {
        var nextQueryResult: SeatLookupLoadResult =
            SeatLookupLoadResult.Success(
                SeatLookupData(
                    beginTimeEpochSeconds = expectedEpoch("2026-04-11T09:00:00"),
                    durationHours = 4,
                    peopleCount = 1,
                    rooms =
                        listOf(
                            SeatRoomSnapshot(
                                roomId = "room-2f",
                                roomName = "自习室圆形二楼",
                                storey = "2F",
                                availableCount = 2,
                                seatNumbers = listOf("166", "168"),
                                recommendedSeatNumber = "166",
                            ),
                        ),
                ),
            )
        var lastQuery: SeatLookupQuery? = null

        override suspend fun loadDefaultSeats(): SeatLookupLoadResult = nextQueryResult

        override suspend fun loadSeats(query: SeatLookupQuery): SeatLookupLoadResult {
            lastQuery = query
            return nextQueryResult
        }
    }

    private class FakeManualReservationRepository : ManualReservationGateway {
        var lastSelection: ManualReservationSelection? = null

        override suspend fun reserve(selection: ManualReservationSelection): ManualReservationResult {
            lastSelection = selection
            return ManualReservationResult.Success(
                taskId = "booking-166",
                bookingId = "booking-166",
                message = "已提交 自习室圆形二楼 166 号座位预约",
            )
        }
    }

    private class FakeSessionRepository(
        activeStudentId: String,
    ) : SessionRepository {
        private val state = MutableStateFlow(fakeSession(activeStudentId))
        private val sessions =
            linkedMapOf(
                activeStudentId to fakeSession(activeStudentId),
            )
        private var currentStudentId: String? = activeStudentId

        override val session: StateFlow<AuthenticatedSession?> = state.asStateFlow()

        override fun currentSession(): AuthenticatedSession? = state.value

        override fun currentSession(studentId: String): AuthenticatedSession? = sessions[studentId]

        override fun activeStudentId(): String? = currentStudentId

        override fun activate(studentId: String): Boolean {
            val saved = sessions[studentId] ?: return false
            currentStudentId = studentId
            state.value = saved
            return true
        }

        override fun save(session: AuthenticatedSession) = Unit

        override fun save(
            studentId: String,
            session: AuthenticatedSession,
            activate: Boolean,
        ) = Unit

        override fun remove(studentId: String) = Unit

        override fun clear() = Unit
    }

    private companion object {
        val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")

        fun expectedEpoch(localDateTime: String): Int =
            LocalDateTime.parse(localDateTime).atZone(zoneId).toEpochSecond().toInt()

        fun fakeSession(studentId: String): AuthenticatedSession =
            AuthenticatedSession(
                session = SessionBundle(cookieHeader = "auth=token-$studentId", userId = studentId),
                cookies = emptyList(),
                currentUserJson = """{"id":"$studentId"}""",
                origin = "https://example.com",
                installationId = "install-$studentId",
            )
    }
}
