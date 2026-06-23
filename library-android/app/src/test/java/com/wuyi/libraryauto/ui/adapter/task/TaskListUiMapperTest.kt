package com.wuyi.libraryauto.ui.adapter.task

import com.google.common.truth.Truth.assertThat
import com.wuyi.libraryauto.core.domain.model.ReservationTaskState
import com.wuyi.libraryauto.core.storage.db.ReservationTaskEntity
import java.time.ZoneId
import org.junit.Test

class TaskListUiMapperTest {

    @Test
    fun `maps failed manual action entity to negative status and appends last error`() {
        val entity =
            ReservationTaskEntity(
                id = "task-123456",
                seatNumber = " ",
                state = ReservationTaskState.FAILED_MANUAL_ACTION,
                bookingId = null,
                startTimeEpochSeconds = 1_710_000_000L,
                limitSignAgoSeconds = 900L,
                expectedMinorsCsv = "1,2,3",
                lastError = "network timeout",
            )

        val uiModel = entity.toTaskListItemUiModel(zoneId = ZoneId.of("UTC"))

        assertThat(uiModel.title).isEqualTo("预约任务 · 123456")
        assertThat(uiModel.seatText).isEqualTo("暂未分配座位")
        assertThat(uiModel.status.text).isEqualTo("需处理")
        assertThat(uiModel.status.tone).isEqualTo(TaskStatusTone.NEGATIVE)
        assertThat(uiModel.description).contains("最近错误：network timeout")
        assertThat(uiModel.timeText).startsWith("开始 ")
        assertThat(uiModel.timeText).contains(" · 截止 ")
    }
}
