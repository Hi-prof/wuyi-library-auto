package com.wuyi.libraryauto.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wuyi.libraryauto.ui.repository.SchoolPortalConfig
import com.wuyi.libraryauto.ui.repository.seat.ManualReservationGateway
import com.wuyi.libraryauto.ui.repository.seat.ManualReservationResult
import com.wuyi.libraryauto.ui.repository.seat.ManualReservationSelection
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupLoadResult
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupQuery
import com.wuyi.libraryauto.ui.repository.seat.SeatLookupRepository
import com.wuyi.libraryauto.ui.repository.seat.SeatRoomSnapshot
import com.wuyi.libraryauto.ui.repository.session.SessionRepository
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

class ManualReservationViewModel(
    private val accountRepository: SavedAccountRepository,
    private val seatLookupRepository: SeatLookupRepository,
    private val manualReservationRepository: ManualReservationGateway,
    private val sessionRepository: SessionRepository,
    private val defaultEntryUrl: String = SchoolPortalConfig.SeatEntryUrls.firstOrNull().orEmpty(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    var uiState by mutableStateOf(buildInitialState())
        private set

    fun refreshAccounts() {
        val accounts = buildAccounts()
        val selectedStudentId =
            resolveSelectedStudentId(
                accounts = accounts,
                preferredStudentId = uiState.selectedStudentId,
            )
        uiState =
            uiState.copy(
                accounts = accounts,
                selectedStudentId = selectedStudentId,
            )
    }

    fun updateSelectedStudentId(studentId: String) {
        if (studentId == uiState.selectedStudentId) {
            return
        }
        uiState =
            uiState.copy(
                selectedStudentId = studentId,
                rooms = emptyList(),
                selectedRoomId = "",
                selectedRoomName = "",
                selectedSeatNumber = "",
                querySummary = "",
                statusMessage = null,
            )
    }

    fun updateSelectedDate(date: String) {
        uiState = clearQuery(uiState.copy(selectedDate = date.trim(), statusMessage = null))
    }

    fun updateSelectedStartTime(startTime: String) {
        uiState = clearQuery(uiState.copy(selectedStartTime = startTime.trim(), statusMessage = null))
    }

    fun updateDurationHours(durationHours: String) {
        uiState = clearQuery(uiState.copy(durationHours = durationHours.trim(), statusMessage = null))
    }

    fun querySeats() {
        val query = buildSeatLookupQuery() ?: return
        if (uiState.isLoadingSeats || uiState.isSubmitting) {
            return
        }

        viewModelScope.launch {
            uiState = uiState.copy(isLoadingSeats = true, statusMessage = null)
            when (val result = seatLookupRepository.loadSeats(query)) {
                SeatLookupLoadResult.NotLoggedIn ->
                    uiState =
                        uiState.copy(
                            isLoadingSeats = false,
                            statusMessage = NOT_LOGGED_IN_MESSAGE,
                        )

                is SeatLookupLoadResult.Failure ->
                    uiState =
                        uiState.copy(
                            isLoadingSeats = false,
                            statusMessage = result.message,
                        )

                is SeatLookupLoadResult.Success ->
                    applyLookupResult(result.data.rooms, result.data.notice)

                is SeatLookupLoadResult.Empty ->
                    applyLookupResult(
                        result.data.rooms,
                        result.data.notice ?: EMPTY_RESULT_MESSAGE,
                    )
            }
        }
    }

    fun selectSeat(
        roomId: String,
        roomName: String,
        seatNumber: String,
    ) {
        uiState =
            uiState.copy(
                selectedRoomId = roomId.trim(),
                selectedRoomName = roomName.trim(),
                selectedSeatNumber = seatNumber.trim(),
                statusMessage = null,
            )
    }

    fun reserveSelectedSeat() {
        val selection = buildReservationSelection() ?: return
        if (uiState.isSubmitting) {
            return
        }

        viewModelScope.launch {
            uiState = uiState.copy(isSubmitting = true, statusMessage = null)
            uiState =
                when (val result = manualReservationRepository.reserve(selection)) {
                    ManualReservationResult.NotLoggedIn ->
                        uiState.copy(
                            isSubmitting = false,
                            statusMessage = NOT_LOGGED_IN_MESSAGE,
                        )

                    is ManualReservationResult.Failure ->
                        uiState.copy(
                            isSubmitting = false,
                            statusMessage = result.message,
                        )

                    is ManualReservationResult.Success ->
                        uiState.copy(
                            isSubmitting = false,
                            statusMessage = result.message,
                        )
                }
        }
    }

    private fun buildInitialState(): ManualReservationUiState {
        val accounts = buildAccounts()
        val now = LocalDateTime.now(clock).withMinute(0).withSecond(0).withNano(0)
        return ManualReservationUiState(
            accounts = accounts,
            selectedStudentId = resolveSelectedStudentId(accounts, sessionRepository.activeStudentId()),
            selectedDate = now.toLocalDate().format(DATE_FORMATTER),
            selectedStartTime = now.toLocalTime().format(TIME_FORMATTER),
        )
    }

    private fun buildAccounts(): List<SavedAccountEntry> =
        accountRepository.readAll().map { account ->
            account.copy(
                isAuthenticated = sessionRepository.currentSession(account.studentId) != null,
                isActive = account.studentId == sessionRepository.activeStudentId(),
            )
        }

    private fun resolveSelectedStudentId(
        accounts: List<SavedAccountEntry>,
        preferredStudentId: String?,
    ): String {
        val safePreferred = preferredStudentId.orEmpty().trim()
        return when {
            safePreferred.isNotBlank() && accounts.any { it.studentId == safePreferred } -> safePreferred
            else -> accounts.firstOrNull()?.studentId.orEmpty()
        }
    }

    private fun buildSeatLookupQuery(): SeatLookupQuery? {
        val window = buildReservationWindow() ?: return null
        val studentId = uiState.selectedStudentId.trim()
        if (studentId.isBlank()) {
            uiState = uiState.copy(statusMessage = "请先添加账号，再查询可选座位。")
            return null
        }
        if (defaultEntryUrl.isBlank()) {
            uiState = uiState.copy(statusMessage = "当前没有配置可用的座位入口。")
            return null
        }
        return SeatLookupQuery(
            studentId = studentId,
            entryUrl = defaultEntryUrl,
            beginTimeEpochSeconds = window.atZone(zoneId).toEpochSecond().toInt(),
            durationSeconds = uiState.durationHours.toInt() * SECONDS_PER_HOUR,
        )
    }

    private fun buildReservationSelection(): ManualReservationSelection? {
        val query = buildSeatLookupQuery() ?: return null
        if (uiState.selectedRoomId.isBlank() || uiState.selectedSeatNumber.isBlank()) {
            uiState = uiState.copy(statusMessage = "请先选择一个座位，再立即预约。")
            return null
        }
        return ManualReservationSelection(
            studentId = query.studentId,
            entryUrl = query.entryUrl,
            roomId = uiState.selectedRoomId,
            roomName = uiState.selectedRoomName,
            seatNumber = uiState.selectedSeatNumber,
            beginTimeEpochSeconds = query.beginTimeEpochSeconds,
            durationSeconds = query.durationSeconds,
        )
    }

    private fun buildReservationWindow(): LocalDateTime? {
        val date =
            runCatching { LocalDate.parse(uiState.selectedDate.trim(), DATE_FORMATTER) }.getOrNull()
                ?: run {
                    uiState = uiState.copy(statusMessage = "日期格式应为 yyyy-MM-dd。")
                    return null
                }
        val time =
            runCatching { LocalTime.parse(uiState.selectedStartTime.trim(), TIME_FORMATTER) }.getOrNull()
                ?: run {
                    uiState = uiState.copy(statusMessage = "开始时间格式应为 HH:mm。")
                    return null
                }
        val durationHours =
            uiState.durationHours.toIntOrNull()?.takeIf { it > 0 } ?: run {
                uiState = uiState.copy(statusMessage = "使用时长必须是大于 0 的小时数。")
                return null
            }
        if (durationHours > MAX_DURATION_HOURS) {
            uiState = uiState.copy(statusMessage = "使用时长不能超过 $MAX_DURATION_HOURS 小时。")
            return null
        }
        return LocalDateTime.of(date, time)
    }

    private fun applyLookupResult(
        rooms: List<SeatRoomSnapshot>,
        notice: String?,
    ) {
        val mappedRooms = rooms.map(::toUiModel)
        val selectedSeatStillExists =
            mappedRooms.any { room ->
                room.roomId == uiState.selectedRoomId &&
                    room.seatNumbers.contains(uiState.selectedSeatNumber)
            }
        uiState =
            uiState.copy(
                rooms = mappedRooms,
                querySummary = buildQuerySummary(),
                selectedRoomId = uiState.selectedRoomId.takeIf { selectedSeatStillExists }.orEmpty(),
                selectedRoomName = uiState.selectedRoomName.takeIf { selectedSeatStillExists }.orEmpty(),
                selectedSeatNumber = uiState.selectedSeatNumber.takeIf { selectedSeatStillExists }.orEmpty(),
                isLoadingSeats = false,
                statusMessage = notice,
            )
    }

    private fun buildQuerySummary(): String {
        val durationHours = uiState.durationHours.ifBlank { DEFAULT_MANUAL_DURATION_HOURS }
        return "${uiState.selectedDate} ${uiState.selectedStartTime} 开始，使用 $durationHours 小时"
    }

    private fun toUiModel(room: SeatRoomSnapshot): ManualReservationRoomUiModel =
        ManualReservationRoomUiModel(
            roomId = room.roomId.ifBlank { room.roomName },
            roomName = room.roomName,
            storey = room.storey,
            availableCount = room.availableCount,
            seatNumbers = room.seatNumbers,
            recommendedSeatNumber = room.recommendedSeatNumber,
        )

    private fun clearQuery(state: ManualReservationUiState): ManualReservationUiState =
        state.copy(
            rooms = emptyList(),
            selectedRoomId = "",
            selectedRoomName = "",
            selectedSeatNumber = "",
            querySummary = "",
        )

    private companion object {
        const val MAX_DURATION_HOURS = 14
        const val EMPTY_RESULT_MESSAGE = "当前条件下没有可用座位，请调整时间后重试。"
        const val NOT_LOGGED_IN_MESSAGE = "当前账号未认证，请先去账号列表刷新认证。"
        const val SECONDS_PER_HOUR = 3600
        val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
