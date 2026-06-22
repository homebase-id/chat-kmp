package id.homebase.core.ui.screens.feed.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.ui.screens.moments.widget.MomentMediaGallery

/** Emoji applied by the double-tap-to-like media gesture. */
private const val DOUBLE_TAP_EMOJI = "❤️"

/**
 * A single feed post: author header → caption → edge-to-edge media → interaction row.
 *
 * M3 Expressive: deliberately NOT a Material `Card`. Each post is a flat full-bleed band on
 * [androidx.compose.material3.ColorScheme.surface]; the list paints a slightly-darker gap between
 * posts (see `FeedTimelineList`), giving the light, modern IG/Facebook stream feel rather than a
 * heavy stack of elevated cards. Media is edge-to-edge. Purely presentational — every action is a
 * callback; double-tapping the media fires the like gesture ([onToggleReaction] with ❤️).
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(bottom = 4.dp),
    ) {
        val authorOdinId = post.originalAuthor ?: post.senderOdinId
        if (authorOdinId != null) {
            PostAuthorHeader(
                authorOdinId = authorOdinId,
                displayName = displayName,
                channelName = channelName,
                createdMs = post.createdMs,
                onAuthorClick = onAuthorClick,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
        }

        if (post.caption.isNotBlank()) {
            PostBody(
                caption = post.caption,
                onExpandFetchFullText = onExpandFetchFullText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // Edge-to-edge — no horizontal padding, no corner clip — for the full-bleed feed look.
        PostMedia(
            post = post,
            onMediaClick = onMediaClick,
            onDoubleTapLike = { onToggleReaction(DOUBLE_TAP_EMOJI) },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )

        PostInteracts(
            reactionSummary = post.reactionPreview,
            ownReactions = post.ownReactions,
            commentCount = post.commentCount,
            reactAccess = post.reactAccess,
            onToggleReaction = onToggleReaction,
            onOpenComments = onOpenComments,
            onShowReactors = onShowReactors,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
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

    MomentMediaGallery(
        payloads = mediaPayloads,
        fileId = post.fileId,
        driveId = post.driveId,
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
    )
}
