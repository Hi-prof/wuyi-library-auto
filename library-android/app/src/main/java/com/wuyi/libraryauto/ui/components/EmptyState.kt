package com.wuyi.libraryauto.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 通用空态组件：上半部分自绘 SVG 风格插画（卡片+座位），下半部分文案+次级 CTA。
 *
 * 通过 Compose Canvas 绘制，不引入额外图片资源，覆盖账号列表 / 自动任务 / 座位展示。
 */
@Composable
fun EmptyStatePanel(
    title: String,
    description: String,
    actionLabel: String? = null,
    actionIcon: ImageVector = Icons.Outlined.Add,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        EmptyStateIllustration(
            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp).height(160.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!actionLabel.isNullOrBlank() && onAction != null) {
            FilledTonalButton(
                onClick = onAction,
                colors =
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            ) {
                Icon(
                    imageVector = actionIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun EmptyStateIllustration(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimary = MaterialTheme.colorScheme.onPrimaryContainer
    val outline = MaterialTheme.colorScheme.outlineVariant

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
            val w = size.width
            val h = size.height

            // 背景渐变光晕
            drawCircle(
                brush =
                    Brush.radialGradient(
                        colors = listOf(primaryContainer.copy(alpha = 0.6f), Color.Transparent),
                        center = Offset(w / 2f, h / 2f),
                        radius = w / 2f,
                    ),
                radius = w / 2f,
                center = Offset(w / 2f, h / 2f),
            )

            // 主图：图书馆桌子（圆角矩形）
            val tableTop = h * 0.45f
            val tableHeight = h * 0.16f
            drawRoundRect(
                color = primary.copy(alpha = 0.18f),
                topLeft = Offset(w * 0.18f, tableTop),
                size = Size(w * 0.64f, tableHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f),
            )
            drawRoundRect(
                color = primary,
                topLeft = Offset(w * 0.18f, tableTop),
                size = Size(w * 0.64f, tableHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f),
                style = Stroke(width = 2f),
            )
            // 桌腿
            drawRoundRect(
                color = primary.copy(alpha = 0.4f),
                topLeft = Offset(w * 0.24f, tableTop + tableHeight),
                size = Size(w * 0.04f, h * 0.22f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
            )
            drawRoundRect(
                color = primary.copy(alpha = 0.4f),
                topLeft = Offset(w * 0.72f, tableTop + tableHeight),
                size = Size(w * 0.04f, h * 0.22f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
            )
            // 桌上书本
            drawRoundRect(
                color = onPrimary.copy(alpha = 0.6f),
                topLeft = Offset(w * 0.32f, tableTop - h * 0.1f),
                size = Size(w * 0.18f, h * 0.1f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f),
            )
            drawRoundRect(
                color = primary,
                topLeft = Offset(w * 0.5f, tableTop - h * 0.14f),
                size = Size(w * 0.16f, h * 0.14f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f),
            )
            // 顶部小灯泡（圆+线）
            drawCircle(
                color = primary,
                radius = h * 0.05f,
                center = Offset(w * 0.5f, h * 0.16f),
            )
            drawCircle(
                color = primaryContainer,
                radius = h * 0.04f,
                center = Offset(w * 0.5f, h * 0.16f),
            )
            // 虚线"光"
            val rays = listOf(
                Offset(-1f, 0f), Offset(1f, 0f), Offset(0f, -1f),
                Offset(-0.7f, -0.7f), Offset(0.7f, -0.7f),
            )
            rays.forEach { dir ->
                val start = Offset(w * 0.5f + dir.x * h * 0.07f, h * 0.16f + dir.y * h * 0.07f)
                val end = Offset(w * 0.5f + dir.x * h * 0.13f, h * 0.16f + dir.y * h * 0.13f)
                drawLine(
                    color = primary.copy(alpha = 0.6f),
                    start = start,
                    end = end,
                    strokeWidth = 2f,
                )
            }
            // 底部分隔虚线
            val path = Path().apply {
                moveTo(w * 0.1f, h * 0.92f)
                lineTo(w * 0.9f, h * 0.92f)
            }
            drawPath(
                path = path,
                color = outline,
                style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))),
            )
        }
    }
}
