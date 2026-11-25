package com.visionvpn.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.visionvpn.app.data.model.LatencyStatus
import com.visionvpn.app.data.model.VisionThemeVariant
import com.visionvpn.app.ui.theme.VisionColors

@Composable
fun VisionBackground(
    themeVariant: VisionThemeVariant = VisionThemeVariant.VISION_NAVY,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    // Fixed to primary Vision UI Dark palette for this release
    val top = VisionColors.BgDeepTop
    val bottom = VisionColors.BgDeepBottom

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(colors = listOf(top, bottom))
            ),
        content = content
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    borderColor: Color = VisionColors.CardBorder,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val baseModifier = modifier
        .clip(shape)
        .background(VisionColors.GlassCardGradient)
        .border(width = 1.dp, color = borderColor, shape = shape)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(contentPadding)

    Column(modifier = baseModifier, content = content)
}

@Composable
fun GradientPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    brush: Brush = VisionColors.PrimaryAccentGradient
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(if (enabled) brush else Brush.linearGradient(listOf(VisionColors.CardBorder, VisionColors.CardBorder)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) VisionColors.TextPrimary else VisionColors.TextSecondary
        )
    }
}

@Composable
fun ProtocolBadge(protocol: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(VisionColors.ElectricBlue.copy(alpha = 0.18f))
            .border(1.dp, VisionColors.ElectricBlue.copy(alpha = 0.45f), shape)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = protocol.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = VisionColors.CyanHighlight
        )
    }
}

@Composable
fun LatencyPill(
    latencyMs: Int?,
    modifier: Modifier = Modifier,
    status: LatencyStatus = LatencyStatus.UNTESTED
) {
    val effectiveStatus = if (status != LatencyStatus.UNTESTED) {
        status
    } else when {
        latencyMs == null -> LatencyStatus.UNTESTED
        latencyMs > 0 -> LatencyStatus.SUCCESS
        else -> LatencyStatus.TIMEOUT
    }

    val (label, color) = when (effectiveStatus) {
        LatencyStatus.UNTESTED -> "—" to VisionColors.TextSecondary
        LatencyStatus.SUCCESS -> {
            val ms = latencyMs?.takeIf { it > 0 }
            if (ms == null) {
                "Failed" to VisionColors.CoralError
            } else if (ms < 250) {
                "$ms ms" to VisionColors.EmeraldConnected
            } else if (ms < 600) {
                "$ms ms" to VisionColors.AmberWarning
            } else {
                "$ms ms" to VisionColors.CoralError
            }
        }
        LatencyStatus.TIMEOUT -> "Timeout" to VisionColors.CoralError
        LatencyStatus.DNS_ERROR -> "DNS Err" to VisionColors.CoralError
        LatencyStatus.TLS_ERROR -> "TLS Err" to VisionColors.CoralError
        LatencyStatus.PROTOCOL_ERROR -> "Proto Err" to VisionColors.CoralError
        LatencyStatus.FAILED -> "Failed" to VisionColors.CoralError
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.4f), shape)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(text = subtitle, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        }
    }
}
