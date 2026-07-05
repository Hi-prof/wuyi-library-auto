package com.wuyi.libraryauto.ui.screen.seat

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.ui.components.StatusTone
import com.wuyi.libraryauto.ui.repository.seat.BatchCheckInResult
import com.wuyi.libraryauto.ui.repository.seat.BatchReservationResult
import com.wuyi.libraryauto.ui.repository.seat.CheckInResult
import com.wuyi.libraryauto.ui.repository.seat.ReservationResult
import java.time.LocalDate
import org.junit.Test

class SeatDisplayBatchResultPresentationTest {
    @Test
    fun `check in result presentation describes all success state`() {
        val presentation =
            buildBatchCheckInResultPresentation(
                result =
                    BatchCheckInResult(
                        total = 2,
                        success = 2,
                        failed = 0,
                        details =
                            listOf(
                                CheckInResult(studentId = "20230001", success = true, message = "已签到"),
                                CheckInResult(studentId = "20230002", success = true, message = "已签到"),
                            ),
                    ),
            )

        assertThat(presentation.title).isEqualTo("签到完成")
        assertThat(presentation.summary).isEqualTo("成功 2 个，失败 0 个")
        assertThat(presentation.statusBadgeLabel).isEqualTo("全部完成")
        assertThat(presentation.statusBadgeTone).isEqualTo(StatusTone.Positive)
        assertThat(presentation.detailTitle).isNull()
        assertThat(presentation.detailLines).isEmpty()
        assertThat(presentation.confirmAction.label).isEqualTo("确定")
    }

    @Test
    fun `check in result presentation limits failure details`() {
        val presentation =
            buildBatchCheckInResultPresentation(
                result =
                    BatchCheckInResult(
                        total = 5,
                        success = 1,
                        failed = 4,
                        details =
                            listOf(
                                CheckInResult(studentId = "20230001", success = true, message = "已签到"),
                                CheckInResult(studentId = "20230002", success = false, message = "失败", error = "网络错误"),
                                CheckInResult(studentId = "20230003", success = false, message = "未认证"),
                                CheckInResult(studentId = "20230004", success = false, message = "失败", error = "座位已释放"),
                                CheckInResult(studentId = "20230005", success = false, message = "失败", error = "超时"),
                            ),
                    ),
            )

        assertThat(presentation.statusBadgeLabel).isEqualTo("部分失败")
        assertThat(presentation.statusBadgeTone).isEqualTo(StatusTone.Warning)
        assertThat(presentation.detailTitle).isEqualTo("失败原因（前3条）")
        assertThat(presentation.detailLines)
            .containsExactly(
                "20230002：网络错误",
                "20230003：未认证",
                "20230004：座位已释放",
            )
            .inOrder()
    }

    @Test
    fun `reservation result presentation includes target date in failures`() {
        val presentation =
            buildBatchReservationResultPresentation(
                result =
                    BatchReservationResult(
                        total = 2,
                        success = 1,
                        failed = 1,
                        details =
                            listOf(
                                ReservationResult(
                                    studentId = "20230001",
                                    targetDate = LocalDate.of(2026, 7, 6),
                                    success = true,
                                    message = "已补约",
                                ),
                                ReservationResult(
                                    studentId = "20230002",
                                    targetDate = LocalDate.of(2026, 7, 7),
                                    success = false,
                                    message = "失败",
                                    error = "没有可用座位",
                                ),
                            ),
                    ),
            )

        assertThat(presentation.title).isEqualTo("补约完成")
        assertThat(presentation.summary).isEqualTo("成功 1 个，失败 1 个")
        assertThat(presentation.statusBadgeLabel).isEqualTo("部分失败")
        assertThat(presentation.statusBadgeTone).isEqualTo(StatusTone.Warning)
        assertThat(presentation.detailTitle).isEqualTo("失败原因（前3条）")
        assertThat(presentation.detailLines).containsExactly("20230002 2026-07-07：没有可用座位")
    }
}
