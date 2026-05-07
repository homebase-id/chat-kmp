package id.homebase.chat.chatappearance.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import id.homebase.chat.chatappearance.model.BubbleContentColor
import id.homebase.chat.chatappearance.model.ChatColor
import id.homebase.chat.chatappearance.model.ChatColorPresets
import id.homebase.chat.chatappearance.model.ChatWallpaper

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

    val bubbleShape = RoundedCornerShape(16.dp)
    val bubbleBg = when (resolvedColor) {
        is ChatColor.Solid -> Modifier.background(Color(resolvedColor.colorArgb), bubbleShape)
        is ChatColor.Gradient -> Modifier.background(
            Brush.linearGradient(resolvedColor.colorsArgb.map { Color(it) }),
            bubbleShape,
        )

        else -> Modifier.background(MaterialTheme.colorScheme.primary, bubbleShape)
    }
    val bubbleTextColor = Color(BubbleContentColor.forBubble(resolvedColor))

    val wallpaperBg = when (wallpaper) {
        is ChatWallpaper.SolidColor -> Modifier.background(Color(wallpaper.colorArgb))
        is ChatWallpaper.GradientColor -> Modifier.background(
            Brush.linearGradient(wallpaper.colorsArgb.map { Color(it) }),
        )

        else -> Modifier.background(MaterialTheme.colorScheme.background)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(wallpaperBg),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            // Received bubble placeholder
            Box(
                modifier = Modifier
                    .align(Alignment.Start)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(16.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Box(
                    Modifier
                        .width(120.dp)
                        .height(12.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                            RoundedCornerShape(4.dp),
                        ),
                )
            }
            // Sent bubble placeholder
            Box(
                modifier = Modifier
                    .align(Alignment.End)
                    .then(bubbleBg)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Box(
                    Modifier
                        .width(140.dp)
                        .height(12.dp)
                        .background(
                            bubbleTextColor.copy(alpha = 0.3f),
                            RoundedCornerShape(4.dp),
                        ),
                )
            }
        }
    }
}
