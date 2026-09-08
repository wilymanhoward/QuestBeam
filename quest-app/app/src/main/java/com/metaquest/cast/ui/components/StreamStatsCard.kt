package com.metaquest.cast.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metaquest.cast.model.StreamMetrics
import com.metaquest.cast.ui.theme.HorizonBorderSubtle
import com.metaquest.cast.ui.theme.HorizonLanGreen
import com.metaquest.cast.ui.theme.HorizonSurfaceElevated
import com.metaquest.cast.ui.theme.HorizonTextMuted
import com.metaquest.cast.ui.theme.HorizonTextPrimary
import com.metaquest.cast.ui.theme.HorizonTextSecondary

/**
 * Real-Time Stream Performance HUD Card
 */
@Composable
fun StreamStatsCard(
    metrics: StreamMetrics,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(HorizonSurfaceElevated)
            .border(1.dp, HorizonBorderSubtle, shape)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatItem(
            label = "LATENCY (RTT)",
            value = if (metrics.rttMs > 0) "${metrics.rttMs} ms" else "-- ms",
            highlightColor = if (metrics.rttMs in 1..45) HorizonLanGreen else HorizonTextPrimary
        )

        StatItem(
            label = "BITRATE",
            value = if (metrics.bitrateMbps > 0f) "%.1f Mbps".format(metrics.bitrateMbps) else "-- Mbps"
        )

        StatItem(
            label = "FPS",
            value = if (metrics.fps > 0) "${metrics.fps} FPS" else "-- FPS"
        )

        StatItem(
            label = "RESOLUTION",
            value = metrics.resolution
        )
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    highlightColor: Color = HorizonTextPrimary
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = HorizonTextMuted,
            letterSpacing = 0.5.sp
        )
        Text(
            text = value,
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = highlightColor,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
