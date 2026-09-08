package com.metaquest.cast.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import com.metaquest.cast.ui.theme.HorizonBorderSubtle
import com.metaquest.cast.ui.theme.HorizonMetaBlue
import com.metaquest.cast.ui.theme.HorizonSurfaceElevated
import com.metaquest.cast.ui.theme.HorizonTextMuted
import com.metaquest.cast.ui.theme.HorizonTextPrimary
import com.metaquest.cast.ui.theme.HorizonTextSecondary

/**
 * Headset Room Pairing Card
 */
@Composable
fun PairingCard(
    roomCode: String,
    onRoomCodeChange: (String) -> Unit,
    onAutoDetectClick: () -> Unit,
    isDetecting: Boolean,
    modifier: Modifier = Modifier,
    statusMessage: String? = null
) {
    GlassPanel(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "LAPTOP ROOM CODE",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = HorizonTextSecondary,
                letterSpacing = 0.5.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Room Code Input Box
                val shape = RoundedCornerShape(12.dp)
                BasicTextField(
                    value = roomCode,
                    onValueChange = { onRoomCodeChange(it.uppercase()) },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = HorizonTextPrimary,
                        letterSpacing = 2.sp
                    ),
                    cursorBrush = SolidColor(HorizonMetaBlue),
                    modifier = Modifier
                        .weight(1f)
                        .clip(shape)
                        .background(HorizonSurfaceElevated)
                        .border(1.dp, HorizonBorderSubtle, shape)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    decorationBox = { innerTextField ->
                        if (roomCode.isEmpty()) {
                            Text(
                                text = "E.G. Q3-7842",
                                color = HorizonTextMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 18.sp,
                                letterSpacing = 1.sp
                            )
                        }
                        innerTextField()
                    }
                )

                Spacer(modifier = Modifier.width(14.dp))

                // Auto-Detect Button
                QuestPillButton(
                    text = if (isDetecting) "Scanning..." else "Auto-Detect",
                    variant = ButtonVariant.SECONDARY,
                    onClick = onAutoDetectClick,
                    enabled = !isDetecting
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (!statusMessage.isNullOrBlank()) {
                Text(
                    text = statusMessage,
                    fontSize = 12.sp,
                    color = HorizonMetaBlue,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text(
                    text = "Enter the 6-character code shown on your laptop browser receiver.",
                    fontSize = 12.sp,
                    color = HorizonTextMuted
                )
            }
        }
    }
}
