package id.homebase.core.ui.screens.feed.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.common.OdinId
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.PublicAvatar
import id.homebase.core.util.formatTimestamp
import id.homebase.core.util.initials
import id.homebase.resources.MR
import id.homebase.resources.feed_channel_locked
import id.homebase.resources.feed_post_to_channel
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/**
 * Top row of a feed post card: author avatar + display name, an optional
 * "to <channel>" line, and a relative timestamp. Purely presentational — the
 * caller resolves [displayName] and [channelName]; this widget never does a
 * lookup.
 *
 * @param authorOdinId identity used to fetch the avatar (caller passes
 *   `originalAuthor ?: senderOdinId`).
 * @param displayName already-resolved name to render.
 * @param channelName optional channel the post was published to.
 * @param createdMs epoch-ms publish time, rendered via [formatTimestamp].
 * @param onAuthorClick invoked when the avatar or name is tapped.
 */
@Composable
fun PostAuthorHeader(
    authorOdinId: OdinId,
    displayName: String,
    channelName: String?,
    createdMs: Long,
    onAuthorClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PublicAvatar(
            odinId = authorOdinId,
            initials = displayName.initials(),
            options = AvatarOptions(size = 40.dp, onClick = onAuthorClick),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onAuthorClick),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Web `PostMeta` (Meta.tsx) renders the date first, then a separated channel link
            // carrying the lock glyph — so timestamp leads, channel follows.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatTimestamp(Instant.fromEpochMilliseconds(createdMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (!channelName.isNullOrBlank()) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = stringResource(MR.string.feed_channel_locked),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 6.dp, end = 3.dp)
                            .size(12.dp),
                    )
                    Text(
                        text = stringResource(MR.string.feed_post_to_channel, channelName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
