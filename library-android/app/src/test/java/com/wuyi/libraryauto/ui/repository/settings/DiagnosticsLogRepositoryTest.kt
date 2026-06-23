package com.wuyi.libraryauto.ui.repository.settings

import android.app.Application
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import com.wuyi.libraryauto.core.runtime.diagnostics.LocalDiagnosticLogRepository
import com.wuyi.libraryauto.core.storage.db.ExecutionLogDao
import com.wuyi.libraryauto.core.storage.db.ExecutionLogEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DiagnosticsLogRepositoryTest {
    private lateinit var localDiagnosticLogRepository: LocalDiagnosticLogRepository

    @Before
    fun setUp() {
        localDiagnosticLogRepository =
            LocalDiagnosticLogRepository(ApplicationProvider.getApplicationContext())
        localDiagnosticLogRepository.clear()
    }

    @After
    fun tearDown() {
        localDiagnosticLogRepository.clear()
    }

    @Test
    fun `loadSnapshot merges login seat and execution logs for one click copy`() = runTest {
        val executionLogRepository =
            ExecutionLogRepository(
                executionLogDao =
                    FakeExecutionLogDao(
                        mutableListOf(
                            ExecutionLogEntity(
                                id = 1,
                                taskId = "task-1",
                                state = ReservationTaskState.RESERVED_WAITING_SIGNIN,
                                recordedAtEpochSeconds = 1_776_100_100L,
                                message = "预约成功",
                            ),
                        ),
                    ),
            )
        val loginAuditRepository = LoginAuditRepository(FakeSharedPreferences())
        val seatLookupAuditRepository = SeatLookupAuditRepository(FakeSharedPreferences())
        val seatStatusAuditRepository = SeatStatusAuditRepository(FakeSharedPreferences())
        val seatActionAuditRepository = SeatActionAuditRepository(FakeSharedPreferences())
        loginAuditRepository.recordSuccess(
            studentId = "20231121153",
            loginUrl = "https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            recordedAtEpochSeconds = 1_776_100_000L,
        )
        seatLookupAuditRepository.recordFailure(
            studentId = "20231121153",
            entryUrl = "https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            message = "查询页没有返回 data，当前接口返回：com.Message，CODE=所属空间不存在",
            recordedAtEpochSeconds = 1_776_100_250L,
        )
        seatStatusAuditRepository.recordFailure(
            studentId = "20231121153",
            requestUrl = "https://wuyiu.huitu.zhishulib.com/Seat/Index/myBookingList?LAB_JSON=1",
            message = "读取座位状态失败：NetworkOnMainThreadException",
            recordedAtEpochSeconds = 1_776_100_200L,
        )
        seatActionAuditRepository.recordFailure(
            studentId = "20231121153",
            actionLabel = "取消预约",
            requestUrl = "https://wuyiu.huitu.zhishulib.com/Seat/Index/cancelBook?bookingId=booking-1&LAB_JSON=1",
            message = "执行取消预约失败：请重新登录",
            recordedAtEpochSeconds = 1_776_100_300L,
        )
        val repository =
            DiagnosticsLogRepository(
                executionLogRepository = executionLogRepository,
                loginAuditRepository = loginAuditRepository,
                seatStatusAuditRepository = seatStatusAuditRepository,
                seatLookupAuditRepository = seatLookupAuditRepository,
                seatActionAuditRepository = seatActionAuditRepository,
            )

        val snapshot = repository.loadSnapshot()
        val copyText = checkNotNull(repository.buildCopyText(snapshot))

        assertThat(snapshot.entries).hasSize(5)
        assertThat(snapshot.entries.first().sourceLabel).isEqualTo("账号动作")
        assertThat(snapshot.entries[1].sourceLabel).isEqualTo("手动查座")
        assertThat(snapshot.entries[2].sourceLabel).isEqualTo("座位状态")
        assertThat(copyText).contains("登录诊断")
        assertThat(copyText).contains("账号动作")
        assertThat(copyText).contains("手动查座")
        assertThat(copyText).contains("座位状态")
        assertThat(copyText).contains("运行日志")
    }

    @Test
    fun `loadSnapshot includes local process diagnostics`() = runTest {
        val executionLogRepository = ExecutionLogRepository(FakeExecutionLogDao(mutableListOf()))
        localDiagnosticLogRepository.append(
            level = "WARN",
            source = "Process",
            title = "上次进程没有正常收尾记录",
            detailLines = listOf("process=com.wuyi.libraryauto", "note=可能是系统回收"),
            recordedAtEpochMillis = 1_776_100_000_000L,
        )
        val repository =
            DiagnosticsLogRepository(
                executionLogRepository = executionLogRepository,
                loginAuditRepository = LoginAuditRepository(FakeSharedPreferences()),
                seatStatusAuditRepository = SeatStatusAuditRepository(FakeSharedPreferences()),
                seatLookupAuditRepository = SeatLookupAuditRepository(FakeSharedPreferences()),
                seatActionAuditRepository = SeatActionAuditRepository(FakeSharedPreferences()),
                localDiagnosticLogRepository = localDiagnosticLogRepository,
            )

        val snapshot = repository.loadSnapshot()
        val copyText = checkNotNull(repository.buildCopyText(snapshot))

        assertThat(snapshot.entries).hasSize(1)
        assertThat(snapshot.entries.single().sourceLabel).isEqualTo("本机诊断")
        assertThat(snapshot.entries.single().title).contains("Process")
        assertThat(copyText).contains("上次进程没有正常收尾记录")
        assertThat(copyText).contains("可能是系统回收")
    }

    @Test
    fun `clearAll clears all diagnostics stores`() = runTest {
        val executionLogDao =
            FakeExecutionLogDao(
                mutableListOf(
                    ExecutionLogEntity(
                        id = 1,
                        taskId = "task-1",
                        state = ReservationTaskState.RESERVED_WAITING_SIGNIN,
                        recordedAtEpochSeconds = 1_776_100_100L,
                        message = "预约成功",
                    ),
                ),
            )
        val executionLogRepository = ExecutionLogRepository(executionLogDao)
        val loginAuditRepository = LoginAuditRepository(FakeSharedPreferences())
        val seatLookupAuditRepository = SeatLookupAuditRepository(FakeSharedPreferences())
        val seatStatusAuditRepository = SeatStatusAuditRepository(FakeSharedPreferences())
        val seatActionAuditRepository = SeatActionAuditRepository(FakeSharedPreferences())
        loginAuditRepository.recordSuccess(
            studentId = "20231121153",
            loginUrl = "https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
        )
        seatLookupAuditRepository.recordFailure(
            studentId = "20231121153",
            entryUrl = "https://wuyiu.huitu.zhishulib.com/#!/Space/Category/list",
            message = "失败",
        )
        seatStatusAuditRepository.recordFailure(
            studentId = "20231121153",
            requestUrl = "https://wuyiu.huitu.zhishulib.com/Seat/Index/myBookingList?LAB_JSON=1",
            message = "失败",
        )
        seatActionAuditRepository.recordFailure(
            studentId = "20231121153",
            actionLabel = "取消预约",
            requestUrl = "https://wuyiu.huitu.zhishulib.com/Seat/Index/cancelBook?bookingId=booking-1&LAB_JSON=1",
            message = "失败",
        )
        localDiagnosticLogRepository.append(
            level = "ERROR",
            source = "Crash",
            title = "未捕获异常",
        )
        val repository =
            DiagnosticsLogRepository(
                executionLogRepository = executionLogRepository,
                loginAuditRepository = loginAuditRepository,
                seatStatusAuditRepository = seatStatusAuditRepository,
                seatLookupAuditRepository = seatLookupAuditRepository,
                seatActionAuditRepository = seatActionAuditRepository,
                localDiagnosticLogRepository = localDiagnosticLogRepository,
            )

        repository.clearAll()
        val snapshot = repository.loadSnapshot()

        assertThat(snapshot.entries).isEmpty()
        assertThat(executionLogDao.logs).isEmpty()
        assertThat(loginAuditRepository.loadLatest()).isNull()
        assertThat(seatLookupAuditRepository.loadLatest()).isNull()
        assertThat(seatStatusAuditRepository.loadLatest()).isNull()
        assertThat(seatActionAuditRepository.loadLatest()).isNull()
        assertThat(localDiagnosticLogRepository.loadEntries()).isEmpty()
    }

    private class FakeExecutionLogDao(
        val logs: MutableList<ExecutionLogEntity>,
    ) : ExecutionLogDao {
        override suspend fun insert(log: ExecutionLogEntity) {
            logs += log
        }

        override suspend fun listAllNewestFirst(): List<ExecutionLogEntity> = logs.toList()

        override suspend fun clearAll() {
            logs.clear()
        }
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
}
