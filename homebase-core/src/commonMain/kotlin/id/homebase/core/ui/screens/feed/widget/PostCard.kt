package id.homebase.core.ui.screens.feed.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.ui.screens.moments.widget.MomentMediaGallery

/** Emoji applied by the double-tap-to-like media gesture. */
private const val DOUBLE_TAP_EMOJI = "❤️"

/**
 * A single feed post rendered as an M3 card: author header → caption body →
 * media gallery → interaction row. Purely presentational — every action is a
 * callback. Double-tapping the media fires the like gesture
 * ([onToggleReaction] with ❤️), mirroring the web feed's
 * `DoubleClickHeartForMedia`.
 *
 * @param post the deserialised post the card renders.
 * @param displayName resolved author name (caller-provided).
 * @param channelName optional channel name shown as "to <channel>".
 * @param onMediaClick opens media at the given index (0-based).
 */
@Composable
fun PostCard(
    post: FeedPostItem,
    displayName: String,
    channelName: String?,
    onPostClick: () -> Unit,
    onAuthorClick: () -> Unit,
    onMediaClick: (index: Int) -> Unit,
    onToggleReaction: (String) -> Unit,
    onOpenComments: () -> Unit,
    onShowReactors: () -> Unit,
    modifier: Modifier = Modifier,
    onExpandFetchFullText: (suspend () -> String?)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val authorOdinId = post.originalAuthor ?: post.senderOdinId
            if (authorOdinId != null) {
                PostAuthorHeader(
                    authorOdinId = authorOdinId,
                    displayName = displayName,
                    channelName = channelName,
                    createdMs = post.createdMs,
                    onAuthorClick = onAuthorClick,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                )
            }

            if (post.caption.isNotBlank()) {
                PostBody(
                    caption = post.caption,
                    onExpandFetchFullText = onExpandFetchFullText,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            PostMedia(
                post = post,
                onMediaClick = onMediaClick,
                onDoubleTapLike = { onToggleReaction(DOUBLE_TAP_EMOJI) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )

            PostInteracts(
                reactionSummary = post.reactionPreview,
                ownReactions = post.ownReactions,
                commentCount = post.commentCount,
                reactAccess = post.reactAccess,
                onToggleReaction = onToggleReaction,
                onOpenComments = onOpenComments,
                onShowReactors = onShowReactors,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * The post's media payloads (key prefix [FeedProtocol.MediaPayloadKeyPrefix]) rendered
 * through the feed-shaped [MomentMediaGallery] — aspect-driven, full-width, with an
 * Instagram-style carousel for 2+. Translates the gallery's payload-keyed click into the
 * card's 0-based [onMediaClick] index and forwards the double-tap-to-like gesture.
 * Renders nothing when the post has no media payloads.
 */
@Composable
private fun PostMedia(
    post: FeedPostItem,
    onMediaClick: (index: Int) -> Unit,
    onDoubleTapLike: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mediaPayloads: List<PayloadDescriptor> =
        post.payloads.filter { it.key.startsWith(FeedProtocol.MediaPayloadKeyPrefix) }
    if (mediaPayloads.isEmpty()) return

    // A followed identity's post (remoteOdinId set) keeps its payload bytes on the author's drive:
    // fetch over peer (by globalTransitId) from the author's public-channel drive instead of the
    // local feed-drive reference, whose bytes are "marked as remote".
    // ponytail: assumes the default public-channel drive; posts on a custom channel would need the
    // channel-specific drive derived from PostContent.channelId — add that if such posts appear.
    val mediaDriveId =
        if (post.remoteOdinId != null) SystemDriveConstants.publicPostChannelDrive.alias
        else post.driveId

    MomentMediaGallery(
        payloads = mediaPayloads,
        fileId = post.fileId,
        driveId = mediaDriveId,
        previewThumbnail = post.previewThumbnail,
        keyHeader = post.keyHeader,
        modifier = modifier,
        onMediaClick = { payload ->
            val index = mediaPayloads.indexOf(payload).coerceAtLeast(0)
            onMediaClick(index)
        },
        onDoubleTap = onDoubleTapLike,
        sharedTransitionScope = null,
        animatedVisibilityScope = null,
        messageId = post.id,
        downloadingFiles = emptySet(),
        remoteOdinId = post.remoteOdinId,
        globalTransitId = post.globalTransitId,
    )
}
