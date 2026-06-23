package com.wuyi.libraryauto.ui.repository.task

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import com.wuyi.libraryauto.core.network.seat.SeatBookingLiveState
import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.core.network.auth.SessionBundle
import com.wuyi.libraryauto.core.storage.db.AutomationPlanDao
import com.wuyi.libraryauto.core.storage.db.AutomationPlanEntity
import com.wuyi.libraryauto.core.storage.db.ReservationTaskDao
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import com.wuyi.libraryauto.ui.repository.session.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AccountStatusRepositoryTest {

    @Test
    fun `load combines current account auth plan result and latest task state`() = runTest {
        val repository =
            AccountStatusRepository(
                accountSource =
                    FakeStoredAccountSource(
                        listOf(
                            StoredAccountSnapshot(
                                studentId = "20230001",
                                preferredRoomName = "自习室圆形二楼",
                                preferredSeatNumber = "166",
                            ),
                            StoredAccountSnapshot(studentId = "20230002"),
                        ),
                    ),
                sessionRepository =
                    FakeSessionRepository().apply {
                        save(studentId = "20230001", session = fakeSession("20230001"))
                    },
                automationPlanDao =
                    FakeAutomationPlanDao(
                        listOf(
                            AutomationPlanEntity(
                                planId = "plan-1",
                                studentId = "20230001",
                                roomName = "自习室圆形二楼",
                                seatNumber = "166",
                                mode = "CONTINUOUS",
                                singleDate = null,
                                singleStartTime = null,
                                singleEndTime = null,
                                enabled = true,
                                createdAtEpochSeconds = 100L,
                                updatedAtEpochSeconds = 200L,
                                nextRunAtEpochSeconds = 300L,
                                lastRunAtEpochSeconds = 150L,
                                lastResultMessage = "待执行",
                            ),
                        ),
                    ),
                reservationTaskDao =
                    FakeReservationTaskDao(
                        mapOf(
                            "20230001" to
                                ReservationTaskEntity(
                                    id = "task-1",
                                    studentId = "20230001",
                                    roomName = "自习室圆形二楼",
                                    seatNumber = "166",
                                    state = ReservationTaskState.RESERVED_WAITING_SIGNIN,
                                    bookingId = "booking-1",
                                    startTimeEpochSeconds = 1_712_800_000L,
                                    limitSignAgoSeconds = 900L,
                                    expectedMinorsCsv = "",
                                    lastError = null,
                                ),
                        ),
                    ),
            )

        assertThat(repository.load()).containsExactly(
            AccountCardStatus(
                studentId = "20230001",
                preferredSeatLabel = "自习室圆形二楼 / 166",
                isCurrent = true,
                isAuthenticated = true,
                latestPlanMessage = "待执行",
                latestTaskState = ReservationTaskState.RESERVED_WAITING_SIGNIN.name,
                liveState = "idle",
                statusLabel = "待执行",
                currentBookingLabel = "",
                checkinWindowOpen = false,
                pendingTaskCount = 1,
                primaryAction = null,
                secondaryAction = null,
            ),
            AccountCardStatus(
                studentId = "20230002",
                preferredSeatLabel = "",
                isCurrent = false,
                isAuthenticated = false,
                latestPlanMessage = "",
                latestTaskState = "",
                liveState = "need-login",
                statusLabel = "需登录",
                currentBookingLabel = "",
                checkinWindowOpen = false,
                pendingTaskCount = 0,
                primaryAction = null,
                secondaryAction = null,
            ),
        ).inOrder()
    }

    @Test
    fun `load maps remote waiting signin snapshot into card actions`() = runTest {
        val repository =
            AccountStatusRepository(
                accountSource =
                    FakeStoredAccountSource(
                        listOf(
                            StoredAccountSnapshot(
                                studentId = "20230001",
                                password = "alpha",
                                preferredRoomName = "自习室圆形二楼",
                                preferredSeatNumber = "166",
                            ),
                        ),
                    ),
                sessionRepository =
                    FakeSessionRepository().apply {
                        save(studentId = "20230001", session = fakeSession("20230001"))
                    },
                automationPlanDao = FakeAutomationPlanDao(emptyList()),
                reservationTaskDao = FakeReservationTaskDao(emptyMap()),
                accountSeatActionExecutor =
                    FakeAccountSeatActionExecutor(
                        SeatBookingSnapshotView(
                            liveState = SeatBookingLiveState.RESERVED_WAITING_SIGNIN,
                            bookingId = "booking-1",
                            roomName = "自习室圆形二楼",
                            seatNumber = "166",
                            beginLabel = "2026-04-11 09:00",
                            statusLabel = "待签到",
                            checkinWindowOpen = false,
                        ),
                    ),
            )

        val card = repository.load().single()

        assertThat(card.liveState).isEqualTo("reserved-waiting-signin")
        assertThat(card.statusLabel).isEqualTo("待签到")
        assertThat(card.currentBookingLabel).isEqualTo("自习室圆形二楼 / 166 / 2026-04-11 09:00")
        assertThat(card.primaryAction).isEqualTo(AccountSeatAction.CheckIn)
        assertThat(card.secondaryAction).isEqualTo(AccountSeatAction.CancelBooking)
        assertThat(card.checkinWindowOpen).isFalse()
    }

    @Test
    fun `load falls back to need login when remote snapshot loading fails`() = runTest {
        val executor = CountingAccountSeatActionExecutor()
        val repository =
            AccountStatusRepository(
                accountSource =
                    FakeStoredAccountSource(
                        listOf(
                            StoredAccountSnapshot(
                                studentId = "20230001",
                                password = "alpha",
                            ),
                        ),
                    ),
                sessionRepository = FakeSessionRepository(),
                automationPlanDao = FakeAutomationPlanDao(emptyList()),
                reservationTaskDao = FakeReservationTaskDao(emptyMap()),
                accountSeatActionExecutor = executor,
            )

        val card = repository.load().single()

        assertThat(card.liveState).isEqualTo("need-login")
        assertThat(card.statusLabel).isEqualTo("需登录")
        assertThat(card.primaryAction).isNull()
        assertThat(card.secondaryAction).isNull()
        assertThat(executor.loadSnapshotCallCount).isEqualTo(0)
    }

    @Test
    fun `load keeps authenticated account out of need login when remote snapshot loading fails`() = runTest {
        val repository =
            AccountStatusRepository(
                accountSource =
                    FakeStoredAccountSource(
                        listOf(
                            StoredAccountSnapshot(
                                studentId = "20230001",
                                password = "alpha",
                            ),
                        ),
                    ),
                sessionRepository =
                    FakeSessionRepository().apply {
                        save(studentId = "20230001", session = fakeSession("20230001"))
                    },
                automationPlanDao = FakeAutomationPlanDao(emptyList()),
                reservationTaskDao = FakeReservationTaskDao(emptyMap()),
                accountSeatActionExecutor = FailingAccountSeatActionExecutor(),
            )

        val card = repository.load().single()

        assertThat(card.isAuthenticated).isTrue()
        assertThat(card.liveState).isEqualTo("idle")
        assertThat(card.statusLabel).isEqualTo("已认证，但当前座位状态获取失败，请稍后刷新。")
        assertThat(card.primaryAction).isNull()
        assertThat(card.secondaryAction).isNull()
    }

    private class FakeStoredAccountSource(
        private val accounts: List<StoredAccountSnapshot>,
    ) : StoredAccountSource {
        override fun readStoredAccounts(): List<StoredAccountSnapshot> = accounts
    }

    private class FakeAutomationPlanDao(
        plans: List<AutomationPlanEntity>,
    ) : AutomationPlanDao {
        private val state = MutableStateFlow(plans)

        override suspend fun upsert(plan: AutomationPlanEntity) {
            state.value = listOf(plan) + state.value.filterNot { it.planId == plan.planId }
        }

        override fun observeAll(): Flow<List<AutomationPlanEntity>> = state

        override fun observeEnabledPlans(): Flow<List<AutomationPlanEntity>> =
            MutableStateFlow(state.value.filter { it.enabled })

        override suspend fun findById(planId: String): AutomationPlanEntity? =
            state.value.firstOrNull { it.planId == planId }

        override suspend fun findLatestEnabledByStudentId(studentId: String): AutomationPlanEntity? =
            state.value
                .filter { it.studentId == studentId && it.enabled }
                .sortedWith(compareByDescending<AutomationPlanEntity> { it.updatedAtEpochSeconds }.thenBy { it.planId })
                .firstOrNull()

        override suspend fun deleteById(planId: String) {
            state.value = state.value.filterNot { it.planId == planId }
        }

        override suspend fun findAll(): List<AutomationPlanEntity> = state.value

        override suspend fun deleteByPlanIdPrefix(planIdPrefix: String) {
            state.value = state.value.filterNot { it.planId.startsWith(planIdPrefix) }
        }
    }

    private class FakeAccountSeatActionExecutor(
        private val snapshot: SeatBookingSnapshotView,
    ) : AccountSeatActionExecutor {
        override suspend fun loadSnapshot(studentId: String): SeatBookingSnapshotView = snapshot

        override suspend fun loadActiveBookings(studentId: String): List<SeatBookingSnapshotView> =
            listOf(snapshot)

        override suspend fun performAction(
            studentId: String,
            action: AccountSeatAction,
            bookingId: String?,
        ): AccountSeatActionExecutionResult = error("unused")
    }

    private class CountingAccountSeatActionExecutor : AccountSeatActionExecutor {
        var loadSnapshotCallCount: Int = 0
            private set

        override suspend fun loadSnapshot(studentId: String): SeatBookingSnapshotView {
            loadSnapshotCallCount += 1
            error("should not be called without session")
        }

        override suspend fun loadActiveBookings(studentId: String): List<SeatBookingSnapshotView> {
            loadSnapshotCallCount += 1
            error("should not be called without session")
        }

        override suspend fun performAction(
            studentId: String,
            action: AccountSeatAction,
            bookingId: String?,
        ): AccountSeatActionExecutionResult = error("unused")
    }

    private class FailingAccountSeatActionExecutor : AccountSeatActionExecutor {
        override suspend fun loadSnapshot(studentId: String): SeatBookingSnapshotView =
            error("")

        override suspend fun loadActiveBookings(studentId: String): List<SeatBookingSnapshotView> =
            error("")

        override suspend fun performAction(
            studentId: String,
            action: AccountSeatAction,
            bookingId: String?,
        ): AccountSeatActionExecutionResult = error("unused")
    }

    private class FakeReservationTaskDao(
        private val latestTasks: Map<String, ReservationTaskEntity>,
    ) : ReservationTaskDao {
        override suspend fun upsert(task: ReservationTaskEntity) = Unit

        override suspend fun findById(id: String): ReservationTaskEntity? =
            latestTasks.values.firstOrNull { it.id == id }

        override suspend fun findLatestForStudent(studentId: String): ReservationTaskEntity? =
            latestTasks[studentId]

        override suspend fun listForStudent(studentId: String): List<ReservationTaskEntity> =
            latestTasks[studentId]?.let { listOf(it) } ?: emptyList()

        override fun observeAll(): Flow<List<ReservationTaskEntity>> = MutableStateFlow(latestTasks.values.toList())

        override suspend fun listAll(): List<ReservationTaskEntity> = latestTasks.values.toList()
    }

    private class FakeSessionRepository : SessionRepository {
        private val state = MutableStateFlow<AuthenticatedSession?>(null)
        private val sessions = linkedMapOf<String, AuthenticatedSession>()
        private var activeStudentId: String? = null

        override val session: StateFlow<AuthenticatedSession?> = state.asStateFlow()

        override fun currentSession(): AuthenticatedSession? = state.value

        override fun currentSession(studentId: String): AuthenticatedSession? = sessions[studentId]

        override fun activeStudentId(): String? = activeStudentId

        override fun activate(studentId: String): Boolean {
            val savedSession = sessions[studentId] ?: return false
            activeStudentId = studentId
            state.value = savedSession
            return true
        }

        override fun save(session: AuthenticatedSession) {
            val studentId = activeStudentId ?: return
            save(studentId = studentId, session = session)
        }

        override fun save(
            studentId: String,
            session: AuthenticatedSession,
            activate: Boolean,
        ) {
            sessions[studentId] = session
            if (activate) {
                activeStudentId = studentId
                state.value = session
            }
        }

        override fun remove(studentId: String) {
            sessions.remove(studentId)
            if (activeStudentId == studentId) {
                activeStudentId = null
                state.value = null
            }
        }

        override fun clear() {
            sessions.clear()
            activeStudentId = null
            state.value = null
        }
    }

    private fun fakeSession(studentId: String): AuthenticatedSession =
        AuthenticatedSession(
            session = SessionBundle(cookieHeader = "auth=token-$studentId", userId = studentId),
            cookies = emptyList(),
            currentUserJson = """{"id":"$studentId"}""",
            origin = "https://example.com",
            installationId = "install-$studentId",
        )
}
