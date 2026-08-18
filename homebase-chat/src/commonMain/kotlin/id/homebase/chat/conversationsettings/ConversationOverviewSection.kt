package id.homebase.chat.conversationsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.chat.widget.MediaItem
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.image.ImageSize
import id.homebase.resources.MR
import id.homebase.resources.contact_info_recent_media
import id.homebase.resources.conversation_media_see_all
import org.jetbrains.compose.resources.stringResource

/**
 * "Recent media" header + horizontal strip of the conversation's shared images and
 * videos, with a "See all" that opens the full grid.
 *
 * Kind-agnostic: rendered by both [ConversationSettingsScreen] (1:1) and
 * `GroupSettingsScreen` (#1157), which previously showed no shared media at all.
 * [ConversationOverview] is keyed by conversation, not by conversation kind, so
 * there is nothing 1:1-specific here — keep it that way.
 *
 * The header appears when the conversation has *anything* shared (files, audio,
 * dice rolls and locations included), because those kinds have no strip of their
 * own and are reachable only through "See all" → the media screen's tabs. Only
 * images/videos get the strip.
 *
 * Tiles are deliberately bare — no sender overlay, in a group either. Attribution
 * lives on the "See all" screen (`ConversationMediaScreen`'s files/audio rows show
 * "sender · date") and in the full-screen viewer, so the strip stays a clean
 * visual index.
 */
@Composable
fun ConversationOverviewSection(
    overview: ConversationOverview,
    onMediaClick: (SharedMediaItem) -> Unit,
    onSeeAll: () -> Unit,
) {
    val hasAnything = overview.media.isNotEmpty() || overview.files.isNotEmpty() ||
            overview.audio.isNotEmpty() || overview.diceRolls.isNotEmpty() ||
            overview.locations.isNotEmpty()

    if (hasAnything) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(MR.string.contact_info_recent_media),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSeeAll) {
                Text(text = stringResource(MR.string.conversation_media_see_all))
            }
        }
    }

    if (overview.media.isNotEmpty()) {
        val strip = remember(overview.media) { overview.media.take(50) }
        Spacer(modifier = Modifier.height(4.dp))
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(strip) { item ->
                MediaItem(
                    payload = item.payload,
                    fileId = item.fileId,
                    driveId = chatTargetDrive.alias,
                    previewThumbnail = item.previewThumbnail,
                    keyHeader = item.keyHeader,
                    imageSize = ImageSize.THUMB_MEDIUM,
                    isSticker = item.isSticker,
                    modifier = Modifier.size(76.dp),
                    shape = RoundedCornerShape(12.dp),
                    onClick = { onMediaClick(item) },
                    sharedTransitionScope = null,
                    animatedVisibilityScope = null,
                )
            }
        }
    }
}
