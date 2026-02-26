package id.homebase.chat.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.core.widget.AvatarImage
import id.homebase.resources.MR
import id.homebase.resources.details
import org.jetbrains.compose.resources.stringResource

@Composable
fun AvatarNameDisplay(
    modifier: Modifier = Modifier,
    displayName: String,
    avatarUrl: String?,
    avatarInitials: String,
    avatarTiny: EmbeddedThumb? = null,
    isGroupConversation: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AvatarImage(
                avatarUrl = avatarUrl,
                avatarInitials = avatarInitials,
                avatarTiny = avatarTiny,
                isGroup = isGroupConversation,
                size = 72.dp,
                fontSize = 24.sp,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable {
                    onClick?.invoke()
                }
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.headlineSmall,
                )
                onClick?.let {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = stringResource(MR.string.details)
                    )
                }
            }
        }
    }
}