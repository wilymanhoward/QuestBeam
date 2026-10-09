package com.metaquest.cast.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metaquest.cast.model.CastConfig
import com.metaquest.cast.model.QualityPreset
import com.metaquest.cast.ui.components.GlassPanel
import com.metaquest.cast.ui.theme.HorizonBorderHighlight
import com.metaquest.cast.ui.theme.HorizonBorderSubtle
import com.metaquest.cast.ui.theme.HorizonMetaBlue
import com.metaquest.cast.ui.theme.HorizonSpace
import com.metaquest.cast.ui.theme.HorizonSurfaceElevated
import com.metaquest.cast.ui.theme.HorizonTextMuted
import com.metaquest.cast.ui.theme.HorizonTextPrimary
import com.metaquest.cast.ui.theme.HorizonTextSecondary

/**
 * Settings Screen for Quality, Audio, and Signaling Configuration
 */
@Composable
fun SettingsScreen(
    config: CastConfig,
    onConfigChange: (CastConfig) -> Unit,
    onBackClick: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HorizonSpace)
            .padding(horizontal = 32.dp, vertical = 24.dp)
            .verticalScroll(scrollState)
    ) {
        // Horizon OS Top Bar with explicit Back Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to Home",
                    tint = HorizonTextPrimary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Stream Settings",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = HorizonTextPrimary
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Quality Preset Selector
        GlassPanel(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "VIDEO STREAM QUALITY",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = HorizonTextSecondary,
                    letterSpacing = 0.5.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                QualityPreset.values().forEach { preset ->
                    val isSelected = config.qualityPreset == preset
                    val shape = RoundedCornerShape(12.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(shape)
                            .background(if (isSelected) HorizonSurfaceElevated else HorizonSpace)
                            .border(
                                1.dp,
                                if (isSelected) HorizonBorderHighlight else HorizonBorderSubtle,
                                shape
                            )
                            .clickable { onConfigChange(config.copy(qualityPreset = preset)) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = preset.title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isSelected) HorizonMetaBlue else HorizonTextPrimary
                            )
                            Text(
                                text = preset.description,
                                fontSize = 12.sp,
                                color = HorizonTextMuted
                            )
                        }

                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = HorizonMetaBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // System Game Audio Capture Toggle
        GlassPanel(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Capture Quest Game Audio",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = HorizonTextPrimary
                    )
                    Text(
                        text = "Streams in-game VR sound and spatial audio to the laptop receiver",
                        fontSize = 12.sp,
                        color = HorizonTextMuted
                    )
                }

                Switch(
                    checked = config.captureAudio,
                    onCheckedChange = { onConfigChange(config.copy(captureAudio = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = HorizonTextPrimary,
                        checkedTrackColor = HorizonMetaBlue,
                        uncheckedTrackColor = HorizonSpace
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Signaling Server URL Configuration
        GlassPanel(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "SIGNALING SERVER URL",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = HorizonTextSecondary,
                    letterSpacing = 0.5.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                val shape = RoundedCornerShape(10.dp)
                BasicTextField(
                    value = config.signalingUrl,
                    onValueChange = { onConfigChange(config.copy(signalingUrl = it)) },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = HorizonTextPrimary
                    ),
                    cursorBrush = SolidColor(HorizonMetaBlue),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(HorizonSurfaceElevated)
                        .border(1.dp, HorizonBorderSubtle, shape)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Set to your laptop's local IP (e.g. ws://192.168.0.56:8080) for local Wi-Fi, or your cloud domain for remote streaming.",
                    fontSize = 11.sp,
                    color = HorizonTextMuted
                )
            }
        }
    }
}
