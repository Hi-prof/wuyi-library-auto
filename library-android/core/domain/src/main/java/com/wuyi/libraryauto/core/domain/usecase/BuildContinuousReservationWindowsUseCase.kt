package com.wuyi.libraryauto.core.domain.usecase

import java.time.LocalDateTime
import kotlin.math.max

data class ReservationWindow(
    val targetDate: String,
    val startHour: Int,
    val endHour: Int,
)

class BuildContinuousReservationWindowsUseCase {
    /**
     * 生成「连续模式」自动预约的目标窗口列表。
     *
     * 行为约束（用户口径）：
     * - 学校允许预约今天、明天、后天。
     * - 当天窗口起始时间向后取整到下一个整点，且不早于 8:00；当取整后 >= 22 时跳过当天，避免生成空窗口。
     * - 明天、后天固定 8:00-22:00。
     * - 任何情况下都至少返回 3 个窗口。如果今天被跳过，则向后顺延一天补齐到第三天。
     */
    operator fun invoke(now: LocalDateTime): List<ReservationWindow> {
        val windows = mutableListOf<ReservationWindow>()
        val today = now.toLocalDate()
        val nextAvailableHour =
            if (now.minute == 0 && now.second == 0 && now.nano == 0) {
                now.hour
            } else {
                now.hour + 1
            }
        val todayStartHour = max(nextAvailableHour, DAILY_OPEN_HOUR)
        if (todayStartHour < DAILY_CLOSE_HOUR) {
            windows += ReservationWindow(
                targetDate = today.toString(),
                startHour = todayStartHour,
                endHour = DAILY_CLOSE_HOUR,
            )
        }
        var offsetDays = 1L
        while (windows.size < TARGET_WINDOW_COUNT) {
            windows += ReservationWindow(
                targetDate = today.plusDays(offsetDays).toString(),
                startHour = DAILY_OPEN_HOUR,
                endHour = DAILY_CLOSE_HOUR,
            )
            offsetDays += 1
        }
        return windows
    }

    private companion object {
        private const val DAILY_OPEN_HOUR = 8
        private const val DAILY_CLOSE_HOUR = 22
        private const val TARGET_WINDOW_COUNT = 3
    }
}
