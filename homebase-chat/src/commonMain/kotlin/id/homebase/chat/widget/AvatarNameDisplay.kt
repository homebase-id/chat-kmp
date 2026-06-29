package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ConversationAvatar
import id.homebase.core.avatars.ConversationAvatarModel
import id.homebase.resources.MR
import id.homebase.resources.details
import org.jetbrains.compose.resources.stringResource

@Composable
fun AvatarNameDisplay(
    modifier: Modifier = Modifier,
    displayName: String,
    avatarModel: ConversationAvatarModel,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    // Makes the avatar itself tappable (e.g. open the photo full screen).
    // Null leaves the avatar non-clickable — pass null for initials/placeholder
    // avatars so there's nothing to open. Independent of [onClick], which is the
    // name-row tap.
    onAvatarClick: (() -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ConversationAvatar(
                avatarModel = avatarModel,
                modifier = Modifier.padding(8.dp),
                options = AvatarOptions(
                    size = 72.dp,
                    fontSize = 24.sp,
                    onClick = onAvatarClick,
                ),
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
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
                    textAlign = TextAlign.Center,
                )
                onClick?.let {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = stringResource(MR.string.details)
                    )
                }
            }
            // Secondary line (e.g. the odinId) under the larger display name.
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}