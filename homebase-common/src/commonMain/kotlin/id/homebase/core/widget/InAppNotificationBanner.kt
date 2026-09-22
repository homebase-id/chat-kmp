package id.homebase.core.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.common.OdinId
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.PublicAvatar
import id.homebase.core.notifications.RichNotificationData
import id.homebase.core.ui.theme.withEmojiFont
import id.homebase.core.util.initials

/**
 * Signal-style in-app notification banner displayed at the top of the screen.
 * Shows sender avatar (loaded via URL), name, and message preview. Tappable to navigate.
 */
@Composable
fun InAppNotificationBanner(
    event: RichNotificationData?,
    visible: Boolean,
    onTap: (RichNotificationData) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible && event != null,
        enter = slideInVertically(initialOffsetY = { -it }),
        exit = slideOutVertically(targetOffsetY = { -it }),
        modifier = modifier.statusBarsPadding(),
    ) {
        event?.let { notification ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clickable { onTap(notification) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shadowElevation = 8.dp,
                tonalElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Sender avatar (circular, loaded via URL)
                    PublicAvatar(
                        odinId = OdinId(notification.senderId),
                        initials = notification.senderName.initials(),
                        options = AvatarOptions(size = 40.dp),
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    // Name + message preview
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = notification.senderName.withEmojiFont(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = notification.body.withEmojiFont(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
