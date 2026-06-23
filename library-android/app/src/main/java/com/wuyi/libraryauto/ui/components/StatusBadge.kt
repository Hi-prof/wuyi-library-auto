package com.wuyi.libraryauto.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.wuyi.libraryauto.ui.theme.StatusInfo
import com.wuyi.libraryauto.ui.theme.StatusInfoContainer
import com.wuyi.libraryauto.ui.theme.StatusNegative
import com.wuyi.libraryauto.ui.theme.StatusNegativeContainer
import com.wuyi.libraryauto.ui.theme.StatusPositive
import com.wuyi.libraryauto.ui.theme.StatusPositiveContainer
import com.wuyi.libraryauto.ui.theme.StatusWarning
import com.wuyi.libraryauto.ui.theme.StatusWarningContainer

/**
 * 业务语义色：成功 / 待办 / 警告 / 失败 / 中性，统一徽章样式。
 */
enum class StatusTone {
    Positive,
    Info,
    Warning,
    Negative,
    Neutral,
}

@Composable
fun StatusBadge(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val (container, content) = when (tone) {
        StatusTone.Positive -> StatusPositiveContainer to StatusPositive
        StatusTone.Info -> StatusInfoContainer to StatusInfo
        StatusTone.Warning -> StatusWarningContainer to StatusWarning
        StatusTone.Negative -> StatusNegativeContainer to StatusNegative
        StatusTone.Neutral ->
            MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    StatusChipBox(container = container, content = content, modifier = modifier, icon = icon, text = text)
}

@Composable
private fun StatusChipBox(
    container: Color,
    content: Color,
    modifier: Modifier,
    icon: ImageVector?,
    text: String,
) {
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(50))
                .background(container)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}
