package id.homebase.chat.chatappearance.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import id.homebase.chat.chatappearance.model.ChatColor

@Composable
fun ColorCircleItem(
    chatColor: ChatColor,
    isSelected: Boolean,
    isAutoItem: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(56.dp)
            .then(
                if (isSelected) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                } else {
                    Modifier
                }
            )
            .clip(CircleShape)
            .then(
                when (chatColor) {
                    is ChatColor.Solid -> Modifier.background(
                        Color(chatColor.colorArgb),
                        CircleShape,
                    )

                    is ChatColor.Gradient -> Modifier.background(
                        Brush.linearGradient(chatColor.colorsArgb.map { Color(it) }),
                        CircleShape,
                    )

                    else -> Modifier.background(
                        MaterialTheme.colorScheme.primaryContainer,
                        CircleShape,
                    )
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isAutoItem) {
            Text(
                text = "auto",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
