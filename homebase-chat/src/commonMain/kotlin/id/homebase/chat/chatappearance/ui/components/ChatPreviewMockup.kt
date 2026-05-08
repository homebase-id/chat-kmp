package id.homebase.chat.chatappearance.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.chat.chatappearance.model.BubbleContentColor
import id.homebase.chat.chatappearance.model.ChatColor
import id.homebase.chat.chatappearance.model.ChatColorPresets
import id.homebase.chat.chatappearance.model.ChatWallpaper
import id.homebase.resources.MR
import id.homebase.resources.chat_preview_received_1
import id.homebase.resources.chat_preview_sent
import id.homebase.resources.chat_preview_time_received
import id.homebase.resources.chat_preview_time_sent
import org.jetbrains.compose.resources.stringResource

private val PreviewBubbleCornerRadius = 10.dp
private val PreviewBubbleMaxWidth = 240.dp
private val PreviewBubbleHorizontalPadding = 12.dp
private val PreviewBubbleVerticalPadding = 7.dp

@Composable
fun ChatPreviewMockup(
    chatColor: ChatColor,
    wallpaper: ChatWallpaper,
    modifier: Modifier = Modifier,
) {
    val resolvedColor = when (chatColor) {
        is ChatColor.Auto, is ChatColor.NotSet -> ChatColorPresets.default
        else -> chatColor
    }

    val wallpaperBg: Modifier = when (wallpaper) {
        is ChatWallpaper.SolidColor -> Modifier.background(Color(wallpaper.colorArgb))
        is ChatWallpaper.GradientColor -> Modifier.background(
            angledLinearGradient(
                colors = wallpaper.colorsArgb.map { Color(it) },
                angleDegrees = wallpaper.angleDegrees,
            ),
        )
        else -> Modifier.background(MaterialTheme.colorScheme.background)
    }

    val isDark = isSystemInDarkTheme()
    val showDimOverlay = isDark && wallpaper !is ChatWallpaper.None

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(wallpaperBg),
    ) {
        if (showDimOverlay) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.2f)),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReceivedPreviewBubble(
                text = stringResource(MR.string.chat_preview_received_1),
                timestamp = stringResource(MR.string.chat_preview_time_received),
            )

            SentPreviewBubble(
                text = stringResource(MR.string.chat_preview_sent),
                timestamp = stringResource(MR.string.chat_preview_time_sent),
                chatColor = resolvedColor,
            )
        }
    }
}

@Composable
private fun ReceivedPreviewBubble(
    text: String,
    timestamp: String,
    cornerRadius: Dp = PreviewBubbleCornerRadius,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val backgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val textColor = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = Modifier
            .widthIn(max = PreviewBubbleMaxWidth)
            .background(backgroundColor, shape)
            .padding(
                horizontal = PreviewBubbleHorizontalPadding,
                vertical = PreviewBubbleVerticalPadding,
            ),
    ) {
        Column {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
            )
            Text(
                text = timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun SentPreviewBubble(
    text: String,
    timestamp: String,
    chatColor: ChatColor,
    cornerRadius: Dp = PreviewBubbleCornerRadius,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val bubbleBg: Modifier = when (chatColor) {
        is ChatColor.Solid -> Modifier.background(Color(chatColor.colorArgb), shape)
        is ChatColor.Gradient -> Modifier.background(
            angledLinearGradient(
                colors = chatColor.colorsArgb.map { Color(it) },
                angleDegrees = chatColor.angleDegrees,
            ),
            shape,
        )
        else -> Modifier.background(MaterialTheme.colorScheme.primary, shape)
    }
    val textColor = Color(BubbleContentColor.forBubble(chatColor))

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = PreviewBubbleMaxWidth)
                .then(bubbleBg)
                .padding(
                    horizontal = PreviewBubbleHorizontalPadding,
                    vertical = PreviewBubbleVerticalPadding,
                ),
        ) {
            Column {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                )
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f),
                    )
                    Icon(
                        imageVector = Icons.Default.DoneAll,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = textColor.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}
