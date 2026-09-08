package com.metaquest.cast.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.metaquest.cast.model.StreamMetrics
import com.metaquest.cast.ui.components.ButtonVariant
import com.metaquest.cast.ui.components.NetworkBadge
import com.metaquest.cast.ui.components.PairingCard
import com.metaquest.cast.ui.components.QuestPillButton
import com.metaquest.cast.ui.components.StreamStatsCard
import com.metaquest.cast.ui.theme.HorizonMetaBlue
import com.metaquest.cast.ui.theme.HorizonSpace
import com.metaquest.cast.ui.theme.HorizonSurfaceElevated
import com.metaquest.cast.ui.theme.HorizonTextMuted
import com.metaquest.cast.ui.theme.HorizonTextPrimary
import com.metaquest.cast.ui.theme.HorizonTextSecondary

/**
 * Primary Horizon OS Spatial Dashboard for Meta Quest 3 Screen Mirroring
 */
@Composable
fun HomeScreen(
    isStreaming: Boolean,
    metrics: StreamMetrics,
    roomCode: String,
    onRoomCodeChange: (String) -> Unit,
    onStartCastClick: () -> Unit,
    onStopCastClick: () -> Unit,
    onAutoDetectClick: () -> Unit,
    onSettingsClick: () -> Unit,
    isDetecting: Boolean = false,
    localIpAddress: String = "192.168.0.x",
    isUsbConnected: Boolean = false,
    signalingUrl: String = "",
    connectionStatus: String? = null
) {
    val isUsbMode = isUsbConnected || signalingUrl.contains("127.0.0.1") || signalingUrl.contains("localhost")
    val effectiveMode = if (isStreaming) {
        metrics.networkMode
    } else if (isUsbMode) {
        NetworkMode.USB
    } else {
        NetworkMode.IDLE
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HorizonSpace)
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(HorizonSurfaceElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isStreaming) Icons.Default.CastConnected else Icons.Default.Cast,
                        contentDescription = "Quest Cast",
                        tint = HorizonMetaBlue,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column(modifier = Modifier.padding(start = 14.dp)) {
                    Text(
                        text = "Quest Cast",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = HorizonTextPrimary
                    )
                    Text(
                        text = if (isUsbMode) "Meta Quest 3 Screen Mirror • ⚡ USB-C Direct Bus" else "Meta Quest 3 Screen Mirror • IP: $localIpAddress",
                        fontSize = 12.sp,
                        color = HorizonTextMuted
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                NetworkBadge(
                    mode = effectiveMode,
                    rttMs = if (isStreaming) metrics.rttMs else 0
                )

                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier.padding(start = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = HorizonTextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Center Content: Pairing Card & Live Stats Card
        Column(modifier = Modifier.fillMaxWidth()) {
            PairingCard(
                roomCode = roomCode,
                onRoomCodeChange = onRoomCodeChange,
                onAutoDetectClick = onAutoDetectClick,
                isDetecting = isDetecting,
                statusMessage = connectionStatus
            )

            Spacer(modifier = Modifier.height(16.dp))

            StreamStatsCard(metrics = metrics)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Bottom Action Bar: 60dp Accessible Horizon Pill Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            if (!isStreaming) {
                QuestPillButton(
                    text = "Start Screen Sharing",
                    onClick = onStartCastClick,
                    variant = ButtonVariant.PRIMARY,
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(60.dp),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Cast,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                )
            } else {
                QuestPillButton(
                    text = "Stop Screen Sharing",
                    onClick = onStopCastClick,
                    variant = ButtonVariant.DANGER,
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(60.dp),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                )
            }
        }
    }
}
