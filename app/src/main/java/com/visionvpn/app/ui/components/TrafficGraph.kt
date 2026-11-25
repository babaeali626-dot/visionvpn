package com.visionvpn.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.visionvpn.app.data.model.TrafficSample
import com.visionvpn.app.ui.theme.VisionColors
import java.util.Locale

@Composable
fun TrafficGraphCard(
    history: List<TrafficSample>,
    latest: TrafficSample,
    isPersian: Boolean,
    modifier: Modifier = Modifier
) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = if (isPersian) "دانلود" else "Download",
                    style = MaterialTheme.typography.labelSmall,
                    color = VisionColors.CyanHighlight
                )
                Text(
                    text = "${formatSpeed(latest.downloadBytesPerSec)}  (${formatBytes(latest.totalDownloadBytes)})",
                    style = MaterialTheme.typography.titleMedium,
                    color = VisionColors.TextPrimary
                )
            }
            Column {
                Text(
                    text = if (isPersian) "آپلود" else "Upload",
                    style = MaterialTheme.typography.labelSmall,
                    color = VisionColors.NeonPurple
                )
                Text(
                    text = "${formatSpeed(latest.uploadBytesPerSec)}  (${formatBytes(latest.totalUploadBytes)})",
                    style = MaterialTheme.typography.titleMedium,
                    color = VisionColors.TextPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
        ) {
            val width = size.width
            val height = size.height

            // Subtle horizontal grid lines
            val gridColor = VisionColors.CardBorder.copy(alpha = 0.5f)
            for (i in 1..3) {
                val y = height * (i / 4f)
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            val samples = if (history.size >= 2) history else listOf(TrafficSample(), TrafficSample())
            val maxVal = samples
                .maxOf { maxOf(it.downloadBytesPerSec, it.uploadBytesPerSec) }
                .coerceAtLeast(1024L)
                .toFloat()

            drawSeries(
                values = samples.map { it.downloadBytesPerSec.toFloat() / maxVal },
                lineColor = VisionColors.CyanHighlight,
                width = width,
                height = height
            )
            drawSeries(
                values = samples.map { it.uploadBytesPerSec.toFloat() / maxVal },
                lineColor = VisionColors.NeonPurple,
                width = width,
                height = height
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeries(
    values: List<Float>,
    lineColor: Color,
    width: Float,
    height: Float
) {
    if (values.size < 2) return
    val stepX = width / (values.size - 1).coerceAtLeast(1)
    val linePath = Path()
    val fillPath = Path()

    values.forEachIndexed { index, ratio ->
        val x = index * stepX
        val y = height - (ratio.coerceIn(0f, 1f) * (height - 12f)) - 6f
        if (index == 0) {
            linePath.moveTo(x, y)
            fillPath.moveTo(x, height)
            fillPath.lineTo(x, y)
        } else {
            val prevX = (index - 1) * stepX
            val prevY = height - (values[index - 1].coerceIn(0f, 1f) * (height - 12f)) - 6f
            val midX = (prevX + x) / 2f
            linePath.cubicTo(midX, prevY, midX, y, x, y)
            fillPath.cubicTo(midX, prevY, midX, y, x, y)
        }
    }
    fillPath.lineTo(width, height)
    fillPath.close()

    drawPath(
        path = fillPath,
        brush = Brush.verticalGradient(
            colors = listOf(lineColor.copy(alpha = 0.28f), Color.Transparent)
        )
    )
    drawPath(
        path = linePath,
        color = lineColor,
        style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
    )
}

fun formatSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec < 1024 -> "$bytesPerSec B/s"
        bytesPerSec < 1024 * 1024 -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0)
        else -> String.format(Locale.US, "%.2f MB/s", bytesPerSec / (1024.0 * 1024.0))
    }
}

fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
