package com.metaquest.cast.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metaquest.cast.ui.theme.HorizonBorderHighlight
import com.metaquest.cast.ui.theme.HorizonBorderSubtle
import com.metaquest.cast.ui.theme.HorizonMetaBlue
import com.metaquest.cast.ui.theme.HorizonMetaBlueHover
import com.metaquest.cast.ui.theme.HorizonRecordRed
import com.metaquest.cast.ui.theme.HorizonTextPrimary

enum class ButtonVariant {
    PRIMARY,
    SECONDARY,
    DANGER
}

/**
 * Meta Horizon OS Accessible Pill Button (60dp height for Quest hand tracking & Touch Plus controllers)
 */
@Composable
fun QuestPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.PRIMARY,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(percent = 50)

    val backgroundBrush = when (variant) {
        ButtonVariant.PRIMARY -> Brush.horizontalGradient(
            colors = listOf(HorizonMetaBlue, HorizonMetaBlueHover)
        )
        ButtonVariant.SECONDARY -> Brush.horizontalGradient(
            colors = listOf(Color(0x22FFFFFF), Color(0x11FFFFFF))
        )
        ButtonVariant.DANGER -> Brush.horizontalGradient(
            colors = listOf(HorizonRecordRed, Color(0xFFE02B20))
        )
    }

    val borderColor = when (variant) {
        ButtonVariant.PRIMARY -> HorizonBorderHighlight
        ButtonVariant.SECONDARY -> HorizonBorderSubtle
        ButtonVariant.DANGER -> Color(0x88FF3B30)
    }

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 140.dp, minHeight = 56.dp)
            .clip(shape)
            .background(brush = backgroundBrush)
            .border(1.dp, borderColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                leadingIcon()
                Spacer(modifier = Modifier.width(10.dp))
            }
            Text(
                text = text,
                color = HorizonTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
