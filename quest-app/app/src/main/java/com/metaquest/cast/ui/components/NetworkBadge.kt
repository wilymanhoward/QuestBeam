package com.metaquest.cast.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metaquest.cast.model.NetworkMode
import com.metaquest.cast.ui.theme.HorizonCloudCyan
import com.metaquest.cast.ui.theme.HorizonLanGreen
import com.metaquest.cast.ui.theme.HorizonTextMuted
import com.metaquest.cast.ui.theme.HorizonTextPrimary

/**
 * Real-time Network Routing Badge (Local LAN vs Cloud Relay)
 */
@Composable
fun NetworkBadge(
    mode: NetworkMode,
    rttMs: Int = 0,
    modifier: Modifier = Modifier
) {
    val (dotColor, badgeBg, badgeBorder, labelText) = when (mode) {
        NetworkMode.LAN -> Quadruple(
            HorizonLanGreen,
            Color(0x2200E599),
            Color(0x5500E599),
            if (rttMs > 0) "Local LAN (${rttMs}ms)" else "Local Wi-Fi (Direct P2P)"
        )
        NetworkMode.CLOUD -> Quadruple(
            HorizonCloudCyan,
            Color(0x2200D2FF),
            Color(0x5500D2FF),
            if (rttMs > 0) "Cloud Relay (${rttMs}ms)" else "Cloud WebRTC Relay"
        )
        NetworkMode.IDLE -> Quadruple(
            HorizonTextMuted,
            Color(0x11FFFFFF),
            Color(0x22FFFFFF),
            "Standby / Disconnected"
        )
    }

    val shape = RoundedCornerShape(percent = 50)

    Row(
        modifier = modifier
            .clip(shape)
            .background(badgeBg)
            .border(1.dp, badgeBorder, shape)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = labelText,
            color = HorizonTextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
