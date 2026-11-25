package com.visionvpn.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.visionvpn.app.data.model.VpnConnectionState
import com.visionvpn.app.ui.theme.VisionColors

@Composable
fun ConnectionOrb(
    state: VpnConnectionState,
    statusLabel: String,
    actionHint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "orb_pulse")
    val pulseDuration = if (state.isInProgress) 900 else 2200
    val pulseScale by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = pulseDuration,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.42f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = pulseDuration,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val (primaryGlow, secondaryGlow) = when (state) {
        VpnConnectionState.CONNECTED -> VisionColors.EmeraldConnected to VisionColors.CyanHighlight
        VpnConnectionState.CONNECTING,
        VpnConnectionState.ENGINE_READY,
        VpnConnectionState.VERIFYING_CONNECTIVITY,
        VpnConnectionState.RECONNECTING,
        VpnConnectionState.DISCONNECTING ->
            VisionColors.CyanHighlight to VisionColors.NeonPurple
        VpnConnectionState.ERROR -> VisionColors.CoralError to VisionColors.NeonPurple
        VpnConnectionState.DISCONNECTED -> VisionColors.ElectricBlue to VisionColors.NeonPurple
    }

    Box(
        modifier = modifier.size(220.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(220.dp)) {
            val baseRadius = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryGlow.copy(alpha = 0.34f),
                        secondaryGlow.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    radius = baseRadius * pulseScale
                ),
                radius = baseRadius * pulseScale
            )
            drawCircle(
                color = primaryGlow.copy(alpha = pulseAlpha),
                radius = baseRadius * 0.88f * pulseScale,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        Box(
            modifier = Modifier
                .size(154.dp)
                .clip(CircleShape)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            primaryGlow.copy(alpha = 0.92f),
                            secondaryGlow.copy(alpha = 0.92f)
                        )
                    )
                )
                .border(
                    width = 2.dp,
                    color = Color.White.copy(alpha = 0.28f),
                    shape = CircleShape
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = statusLabel,
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Text(
                    text = actionHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.82f)
                )
            }
        }
    }
}
