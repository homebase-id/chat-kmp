package id.homebase.core.avatars

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import id.homebase.resources.MR
import id.homebase.resources.avatar_default
import org.jetbrains.compose.resources.stringResource

@Composable
fun FallbackAvatar(
    initials: String?,
    options: AvatarOptions,
    modifier: Modifier = Modifier,
    imageVector: ImageVector = Icons.Default.Person
) {
    val fontSize = options.fontSize ?: (options.size.value * 0.4f).sp

    val clickableModifier =
        if (options.onClick != null) {
            modifier
                .size(options.size)
                .clip(CircleShape)
                .clickable { options.onClick.invoke() }
        } else {
            modifier
                .size(options.size)
                .clip(CircleShape)
        }

    Box(
        modifier = clickableModifier.background(Color.Gray, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (!initials.isNullOrBlank()) {
            Text(
                text = initials,
                color = Color.White,
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold
            )
        } else {
            Icon(
                modifier = Modifier.size(options.size / 2),
                imageVector = imageVector,
                contentDescription = stringResource(MR.string.avatar_default),
                tint = Color.White
            )
        }
    }
}