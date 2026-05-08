package id.homebase.chat.chatappearance.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.chat.chatappearance.model.ChatColor
import id.homebase.chat.chatappearance.model.ChatColorPresets
import id.homebase.chat.chatappearance.model.ChatWallpaper
import id.homebase.resources.MR
import id.homebase.resources.chat_preview_contact_name
import org.jetbrains.compose.resources.stringResource

@Composable
fun PhoneFramePreview(
    chatColor: ChatColor,
    wallpaper: ChatWallpaper,
    modifier: Modifier = Modifier,
) {
    val resolvedColor = when (chatColor) {
        is ChatColor.Auto, is ChatColor.NotSet -> ChatColorPresets.default
        else -> chatColor
    }

    val bubbleColor: Color = when (resolvedColor) {
        is ChatColor.Solid -> Color(resolvedColor.colorArgb)
        is ChatColor.Gradient -> Color(resolvedColor.colorsArgb.first())
        else -> MaterialTheme.colorScheme.primary
    }

    val isDark = isSystemInDarkTheme()
    val phoneBg = MaterialTheme.colorScheme.surface
    val chrome = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val iconTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        // Phone container — Signal: 156dp × 288dp, 8dp corners
        Box(
            modifier = Modifier
                .width(180.dp)
                .height(320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(phoneBg),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top bar — back, avatar, name, video, phone
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = iconTint,
                    )
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(chrome, CircleShape),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(MR.string.chat_preview_contact_name),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = iconTint,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = iconTint,
                    )
                }

                // Chat area — wallpaper + bubbles
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .then(
                            when (wallpaper) {
                                is ChatWallpaper.SolidColor ->
                                    Modifier.background(Color(wallpaper.colorArgb))
                                is ChatWallpaper.GradientColor ->
                                    Modifier.background(
                                        angledLinearGradient(
                                            colors = wallpaper.colorsArgb.map { Color(it) },
                                            angleDegrees = wallpaper.angleDegrees,
                                        ),
                                    )
                                else -> Modifier.background(MaterialTheme.colorScheme.background)
                            },
                        ),
                ) {
                    // Dark mode dim overlay
                    if (isDark && wallpaper !is ChatWallpaper.None) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.2f)),
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                    ) {
                        // "Today" pill
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .width(28.dp)
                                .height(10.dp)
                                .background(chrome, RoundedCornerShape(5.dp)),
                        )

                        Spacer(Modifier.height(14.dp))

                        // Received bubble placeholder
                        Box(
                            modifier = Modifier
                                .align(Alignment.Start)
                                .width(100.dp)
                                .height(30.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                    RoundedCornerShape(8.dp),
                                ),
                        )

                        Spacer(Modifier.height(8.dp))

                        // Sent bubble placeholder
                        Box(
                            modifier = Modifier
                                .align(Alignment.End)
                                .width(100.dp)
                                .height(30.dp)
                                .background(bubbleColor, RoundedCornerShape(8.dp)),
                        )
                    }
                }

                // Bottom input bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .background(bubbleColor, CircleShape)
                            .padding(2.dp),
                        tint = Color.White,
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(14.dp)
                            .background(chrome, RoundedCornerShape(7.dp)),
                    )
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = iconTint,
                    )
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = iconTint,
                    )
                }
            }
        }
    }
}
