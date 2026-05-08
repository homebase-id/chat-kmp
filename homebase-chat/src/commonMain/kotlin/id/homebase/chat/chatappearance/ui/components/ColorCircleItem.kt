package id.homebase.chat.chatappearance.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import id.homebase.chat.chatappearance.model.ChatColor
import id.homebase.resources.MR
import id.homebase.resources.chat_color_auto
import org.jetbrains.compose.resources.stringResource

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
                        angledLinearGradient(colors = chatColor.colorsArgb.map { Color(it) }, angleDegrees = chatColor.angleDegrees),
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
                text = stringResource(MR.string.chat_color_auto),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
