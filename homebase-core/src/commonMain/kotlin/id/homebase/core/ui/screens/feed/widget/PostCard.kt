package id.homebase.core.ui.screens.feed.widget

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.ui.screens.moments.widget.MomentMediaGallery
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import id.homebase.core.feed.services.EmbeddedPost
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.resources.MR
import id.homebase.resources.feed_comment_encrypted
import id.homebase.resources.feed_view_all_comments
import org.jetbrains.compose.resources.stringResource

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
    onRepost: (() -> Unit)? = null,
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

        post.embeddedPost?.let { embedded ->
            QuotedPost(
                embedded = embedded,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
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
            onRepost = onRepost,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )

        PostCommentPreview(
            summary = post.reactionPreview,
            onViewAll = onOpenComments,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        )
    }
}

/**
 * FB-style inline preview of the latest 1–2 comments + a "View all" affordance, read straight from
 * the post's [ReactionSummary.comments] (server-supplied with the header — no per-post fetch).
 * Each row and the link open the comments modal. Renders nothing when there are no comments.
 */
@Composable
private fun PostCommentPreview(
    summary: ReactionSummary?,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = summary?.comments.orEmpty().takeLast(2)
    if (shown.isEmpty()) return
    val encryptedLabel = stringResource(MR.string.feed_comment_encrypted)
    Column(modifier = modifier.fillMaxWidth()) {
        shown.forEach { comment ->
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(comment.odinId) }
                    append("  ")
                    append(if (comment.isEncrypted) encryptedLabel else comment.content)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onViewAll)
                    .padding(vertical = 2.dp),
            )
        }
        if (summary != null && summary.totalCommentCount > shown.size) {
            Text(
                text = stringResource(MR.string.feed_view_all_comments),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(onClick = onViewAll)
                    .padding(top = 2.dp),
            )
        }
    }
}

/**
 * A quoted / reposted source post rendered inline as a bordered card: the original author and
 * their caption. Mirrors the web feed's embedded-post block; shown whenever a post carries an
 * [EmbeddedPost] (i.e. it's a repost). The full source is opened separately by its id.
 */
@Composable
private fun QuotedPost(
    embedded: EmbeddedPost,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            embedded.author?.takeIf { it.isNotBlank() }?.let { author ->
                Text(
                    text = author,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            embedded.caption?.takeIf { it.isNotBlank() }?.let { caption ->
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The post's media payloads (key prefix [FeedProtocol.MediaPayloadKeyPrefix]) rendered
 * through the feed-shaped [MomentMediaGallery] — aspect-driven, full-width, with an
 * Instagram-style carousel for 2+. Translates the gallery's payload-keyed click into the
 * card's 0-based [onMediaClick] index and forwards the double-tap-to-like gesture.
 * Renders nothing when the post has no media payloads.
 *
 * ponytail: reads payload bytes from the post's local drive ([FeedPostItem.driveId]). A followed
 * identity's media payloads live on the author's drive ("marked as remote"); the feed list still
 * renders from the header's embedded preview thumbnail. Full-res over-peer fetch is deferred until
 * the v2 by-globalTransitId payload route exists (see project_native_feed).
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

    // Each double-tap bumps the tick so [DoubleTapHeartBurst] replays its pop-and-fade ❤️.
    var burstTick by remember { mutableIntStateOf(0) }

    Box(modifier = modifier) {
        MomentMediaGallery(
            payloads = mediaPayloads,
            fileId = post.fileId,
            driveId = post.driveId,
            previewThumbnail = post.previewThumbnail,
            keyHeader = post.keyHeader,
            modifier = Modifier.fillMaxWidth(),
            onMediaClick = { payload ->
                val index = mediaPayloads.indexOf(payload).coerceAtLeast(0)
                onMediaClick(index)
            },
            onDoubleTap = {
                burstTick++
                onDoubleTapLike()
            },
            sharedTransitionScope = null,
            animatedVisibilityScope = null,
            messageId = post.id,
            downloadingFiles = emptySet(),
        )
        DoubleTapHeartBurst(tick = burstTick)
    }
}

/**
 * The signature like gesture: a big ❤️ that springs up with an overshoot and fades, centred over
 * the media — replayed whenever [tick] changes (each double-tap). Renders nothing before the first
 * tap. Purely decorative; the actual reaction is fired by the caller.
 */
@Composable
private fun BoxScope.DoubleTapHeartBurst(tick: Int) {
    if (tick == 0) return
    val scale = remember { Animatable(0.2f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(tick) {
        alpha.snapTo(0.95f)
        scale.snapTo(0.2f)
        scale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        )
        alpha.animateTo(0f, tween(durationMillis = 260))
    }
    Icon(
        imageVector = Icons.Filled.Favorite,
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier
            .align(Alignment.Center)
            .size(104.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
            },
    )
}
