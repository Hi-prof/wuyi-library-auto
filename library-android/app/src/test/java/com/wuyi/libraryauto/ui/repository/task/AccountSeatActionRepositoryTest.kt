package com.wuyi.libraryauto.ui.repository.task

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.network.auth.AuthenticatedSession
import com.wuyi.libraryauto.core.network.auth.SessionBundle
import com.wuyi.libraryauto.core.network.seat.SeatBookingActionService
import com.wuyi.libraryauto.core.network.seat.SeatBookingLiveState
import com.wuyi.libraryauto.core.network.seat.SeatBookingStatusService
import com.wuyi.libraryauto.core.network.seat.SchoolSeatApi
import com.wuyi.libraryauto.ui.repository.SchoolPortalConfig
import com.wuyi.libraryauto.ui.repository.settings.SeatActionAuditRepository
import com.wuyi.libraryauto.ui.repository.settings.SeatStatusAuditRepository
import com.wuyi.libraryauto.ui.repository.session.SessionRepository
import com.wuyi.libraryauto.ui.viewmodel.LoginGateway
import com.wuyi.libraryauto.ui.viewmodel.LoginResult
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.test.runTest
import org.junit.Test
import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicReference

class AccountSeatActionRepositoryTest {

    @Test
    fun `performCheckIn auto logs in and refreshes booking state`() = runTest {
        val sessionRepository = FakeSessionRepository()
        val loginGateway = FakeLoginGateway(sessionRepository)
        val api =
            FakeSchoolSeatApi(
                bookingListBodies =
                    ArrayDeque(
                        listOf(
                            """{"CODE":"ok","DATA":{"list":[{"id":"booking-1","no":"166","room":"自习室圆形二楼","status":"Reserve","day":"2026-04-11","begin":"09:00","sign_can":1}]}}""",
                            """{"CODE":"ok","DATA":{"list":[{"id":"booking-1","no":"166","room":"自习室圆形二楼","status":"CheckIn","day":"2026-04-11","begin":"09:00","sign_can":0}]}}""",
                        ),
                    ),
                checkInBody = """{"CODE":"ok","MESSAGE":"已签到"}""",
            )
        val repository =
            AccountSeatActionRepository(
                accountSource =
                    FakeStoredAccountSource(
                        listOf(
                            StoredAccountSnapshot(
                                studentId = "20230001",
                                password = "alpha",
                            ),
                        ),
                    ),
                sessionRepository = sessionRepository,
                loginGateway = loginGateway,
                statusServiceFactory = { SeatBookingStatusService(api) },
                actionServiceFactory = { SeatBookingActionService(api) },
                coordinator = AccountOperationCoordinator(),
            )

        val result = repository.performAction("20230001", AccountSeatAction.CheckIn)

        assertThat(loginGateway.lastStudentId).isEqualTo("20230001")
        assertThat(loginGateway.lastPassword).isEqualTo("alpha")
        assertThat(result.message).isEqualTo("已签到")
        assertThat(result.updatedSnapshot.liveState).isEqualTo(SeatBookingLiveState.ACTIVE_SIGNED_IN)
        assertThat(result.updatedSnapshot.statusLabel).isEqualTo("已签到")
        assertThat(api.checkInUrls.single())
            .isEqualTo("https://example.com/Seat/Index/checkIn?bookingId=booking-1&LAB_JSON=1")
    }

    @Test
    fun `loadSnapshot falls back to configured seat service origin when stored session origin is stale`() = runTest {
        val sessionRepository =
            FakeSessionRepository().apply {
                save(
                    studentId = "20230001",
                    session = fakeSession("20230001", origin = "https://legacy.example.com"),
                    activate = true,
                )
            }
        val api =
            FakeSchoolSeatApi(
                bookingListBodiesByUrl =
                    mapOf(
                        "${SchoolPortalConfig.SeatServiceOrigin}/Seat/Index/myBookingList?LAB_JSON=1" to
                            """
                            {"content":{"defaultItems":[{"id":"booking-9","seatNum":"166","roomName":"自习室圆形二楼","status":"0","time":1775869200,"duration":10800,"nowTime":1775868900,"limitSignAgo":900,"limitSignBack":1800}]}}
                            """.trimIndent(),
                    ),
            )
        val repository =
            AccountSeatActionRepository(
                accountSource =
                    FakeStoredAccountSource(
                        listOf(
                            StoredAccountSnapshot(
                                studentId = "20230001",
                                password = "alpha",
                            ),
                        ),
                    ),
                sessionRepository = sessionRepository,
                loginGateway = UnusedLoginGateway,
                statusServiceFactory = { SeatBookingStatusService(api) },
                actionServiceFactory = { SeatBookingActionService(api) },
                coordinator = AccountOperationCoordinator(),
                seatServiceOrigins = listOf(SchoolPortalConfig.SeatServiceOrigin),
            )

        val snapshot = repository.loadSnapshot("20230001")

        assertThat(snapshot.liveState).isEqualTo(SeatBookingLiveState.RESERVED_WAITING_SIGNIN)
        assertThat(snapshot.roomName).isEqualTo("自习室圆形二楼")
        assertThat(snapshot.seatNumber).isEqualTo("166")
        assertThat(api.fetchBookingListUrls)
            .containsExactly(
                "https://legacy.example.com/Seat/Index/myBookingList?LAB_JSON=1",
                "${SchoolPortalConfig.SeatServiceOrigin}/Seat/Index/myBookingList?LAB_JSON=1",
            )
            .inOrder()
    }

    @Test
    fun `loadSnapshot records failure diagnosis when all origins fail`() = runTest {
        val sessionRepository =
            FakeSessionRepository().apply {
                save(
                    studentId = "20230001",
                    session = fakeSession("20230001"),
                    activate = true,
                )
            }
        val seatStatusAuditRepository = SeatStatusAuditRepository(FakeSharedPreferences())
        val repository =
            AccountSeatActionRepository(
                accountSource =
                    FakeStoredAccountSource(
                        listOf(
                            StoredAccountSnapshot(
                                studentId = "20230001",
                                password = "alpha",
                            ),
                        ),
                    ),
                sessionRepository = sessionRepository,
                loginGateway = UnusedLoginGateway,
                statusServiceFactory = { error("网络异常") },
                actionServiceFactory = {
                    SeatBookingActionService(FakeSchoolSeatApi())
                },
                coordinator = AccountOperationCoordinator(),
                seatStatusAuditRepository = seatStatusAuditRepository,
            )

        val error =
            runCatching {
                repository.loadSnapshot("20230001")
            }.exceptionOrNull()

        assertThat(error).isNotNull()
        val audit = seatStatusAuditRepository.loadLatest()
        checkNotNull(audit)
        assertThat(audit.studentId).isEqualTo("20230001")
        assertThat(audit.outcomeLabel).isEqualTo("读取失败")
        assertThat(audit.message).contains("网络异常")
    }

    @Test
    fun `performAction records action failure diagnosis with request url`() = runTest {
        val sessionRepository =
            FakeSessionRepository().apply {
                save(
                    studentId = "20230001",
                    session = fakeSession("20230001"),
                    activate = true,
                )
            }
        val seatActionAuditRepository = SeatActionAuditRepository(FakeSharedPreferences())
        val api =
            FakeSchoolSeatApi(
                bookingListBodies =
                    ArrayDeque(
                        listOf(
                            """{"CODE":"ok","DATA":{"list":[{"id":"booking-1","no":"166","room":"自习室圆形二楼","status":"Reserve","day":"2026-04-11","begin":"09:00","sign_can":1}]}}""",
                        ),
                    ),
                cancelBody =
                    """
                    {
                      "CODE": "ok",
                      "MESSAGE": "请求成功",
                      "DATA": {
                        "result": "fail",
                        "msg": "请重新登录"
                      }
                    }
                    """.trimIndent(),
            )
        val repository =
            AccountSeatActionRepository(
                accountSource =
                    FakeStoredAccountSource(
                        listOf(
                            StoredAccountSnapshot(
                                studentId = "20230001",
                                password = "alpha",
                            ),
                        ),
                    ),
                sessionRepository = sessionRepository,
                loginGateway = UnusedLoginGateway,
                statusServiceFactory = { SeatBookingStatusService(api) },
                actionServiceFactory = { SeatBookingActionService(api) },
                coordinator = AccountOperationCoordinator(),
                seatActionAuditRepository = seatActionAuditRepository,
            )

        val error =
            runCatching {
                repository.performAction("20230001", AccountSeatAction.CancelBooking)
            }.exceptionOrNull()

        assertThat(error).isNotNull()
        assertThat(error).hasMessageThat().contains("请重新登录")
        val audit = seatActionAuditRepository.loadLatest()
        checkNotNull(audit)
        assertThat(audit.studentId).isEqualTo("20230001")
        assertThat(audit.actionLabel).isEqualTo("取消预约")
        assertThat(audit.outcomeLabel).isEqualTo("执行失败")
        assertThat(audit.requestUrl)
            .isEqualTo("https://example.com/Seat/Index/cancelBooking?bookingId=booking-1&LAB_JSON=1")
        assertThat(audit.message).contains("执行取消预约失败")
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    fun `loadSnapshot runs booking query on configured io dispatcher`() = runTest {
        val sessionRepository =
            FakeSessionRepository().apply {
                save(
                    studentId = "20230001",
                    session = fakeSession("20230001"),
                    activate = true,
                )
            }
        val threadName = AtomicReference("")
        val api =
            FakeSchoolSeatApi(
                bookingListBodies =
                    ArrayDeque(
                        listOf(
                            """{"CODE":"ok","DATA":{"list":[{"id":"booking-1","no":"166","room":"自习室圆形二楼","status":"Reserve","day":"2026-04-11","begin":"09:00","sign_can":1}]}}""",
                        ),
                    ),
            )
        val dispatcher = newSingleThreadContext("account-seat-action-io")

        try {
            val repository =
                AccountSeatActionRepository(
                    accountSource =
                        FakeStoredAccountSource(
                            listOf(
                                StoredAccountSnapshot(
                                    studentId = "20230001",
                                    password = "alpha",
                                ),
                            ),
                        ),
                    sessionRepository = sessionRepository,
                    loginGateway = UnusedLoginGateway,
                    statusServiceFactory = {
                        object : SeatBookingStatusService(api) {
                            override fun loadCurrentBooking(url: String): com.wuyi.libraryauto.core.network.seat.SeatBookingSnapshot {
                                threadName.set(Thread.currentThread().name)
                                return super.loadCurrentBooking(url)
                            }
                        }
                    },
                    actionServiceFactory = { SeatBookingActionService(api) },
                    coordinator = AccountOperationCoordinator(),
                    ioDispatcher = dispatcher,
                )

            repository.loadSnapshot("20230001")

            assertThat(threadName.get()).contains("account-seat-action-io")
        } finally {
            dispatcher.close()
        }
    }

    private class FakeStoredAccountSource(
        private val accounts: List<StoredAccountSnapshot>,
    ) : StoredAccountSource {
        override fun readStoredAccounts(): List<StoredAccountSnapshot> = accounts
    }

    private class FakeLoginGateway(
        private val sessionRepository: FakeSessionRepository,
    ) : LoginGateway {
        var lastStudentId: String? = null
            private set
        var lastPassword: String? = null
            private set

        override suspend fun login(
            studentId: String,
            password: String,
        ): LoginResult {
            lastStudentId = studentId
            lastPassword = password
            sessionRepository.save(studentId, fakeSession(studentId), activate = false)
            return LoginResult.Success
        }
    }

    private object UnusedLoginGateway : LoginGateway {
        override suspend fun login(
            studentId: String,
            password: String,
        ): LoginResult = error("unused")
    }

    private class FakeSchoolSeatApi(
        private val bookingListBodies: ArrayDeque<String> = ArrayDeque(),
        private val bookingListBodiesByUrl: Map<String, String> = emptyMap(),
        private val checkInBody: String = """{"CODE":"ok","MESSAGE":"已签到"}""",
        private val checkoutBody: String = """{"CODE":"ok","MESSAGE":"已签退"}""",
        private val cancelBody: String = """{"CODE":"ok","MESSAGE":"已取消预约"}""",
    ) : SchoolSeatApi {
        val fetchBookingListUrls = mutableListOf<String>()
        val checkInUrls = mutableListOf<String>()

        override fun fetchSearchPage(url: String): String = error("unused")

        override fun searchSeats(
            url: String,
            requestBody: String,
        ): String = error("unused")

        override fun reserveSeats(
            url: String,
            requestBody: String,
        ): String = error("unused")

        override fun fetchBookingList(url: String): String {
            fetchBookingListUrls += url
            bookingListBodiesByUrl[url]?.let { return it }
            return bookingListBodies.removeFirstOrNull()
                ?: error("unexpected booking list url: $url")
        }

        override fun checkIn(url: String): String {
            checkInUrls += url
            return checkInBody
        }

        override fun checkout(url: String): String = checkoutBody

        override fun cancelBooking(url: String): String = cancelBody
    }

    private class FakeSessionRepository : SessionRepository {
        private val state = MutableStateFlow<AuthenticatedSession?>(null)
        private val sessions = linkedMapOf<String, AuthenticatedSession>()
        private var activeStudentId: String? = null

        override val session: StateFlow<AuthenticatedSession?> = state.asStateFlow()

        override fun currentSession(): AuthenticatedSession? = state.value

        override fun currentSession(studentId: String): AuthenticatedSession? = sessions[studentId]

        override fun activeStudentId(): String? = activeStudentId

        override fun activate(studentId: String): Boolean = false

        override fun save(session: AuthenticatedSession) = Unit

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

        override fun remove(studentId: String) = Unit

        override fun clear() = Unit
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values

        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues

        override fun getInt(key: String?, defValue: Int): Int = defValue

        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float = defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor =
            object : SharedPreferences.Editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    if (key != null) {
                        values[key] = value
                    }
                    return this
                }

                override fun remove(key: String?): SharedPreferences.Editor {
                    if (key != null) {
                        values.remove(key)
                    }
                    return this
                }

                override fun clear(): SharedPreferences.Editor {
                    values.clear()
                    return this
                }

                override fun commit(): Boolean = true

                override fun apply() = Unit

                override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this

                override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this

                override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
                    .also {
                        if (key != null) {
                            values[key] = value
                        }
                    }

                override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this

                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    private companion object {
        fun fakeSession(
            studentId: String,
            origin: String = "https://example.com",
        ): AuthenticatedSession =
            AuthenticatedSession(
                session = SessionBundle(cookieHeader = "auth=token-$studentId", userId = studentId),
                cookies = emptyList(),
                currentUserJson = """{"id":"$studentId"}""",
                origin = origin,
                installationId = "install-$studentId",
            )
    }
}
