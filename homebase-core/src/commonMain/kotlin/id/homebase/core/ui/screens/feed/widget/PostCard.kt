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
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.builder.LinkPreviewDescriptor
import id.homebase.chat.widget.LinkPreviewCard
import id.homebase.core.feed.services.CanReact
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.authorOdinId
import id.homebase.core.feed.services.toPostAudience
import id.homebase.core.feed.services.previewBody
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
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid
import id.homebase.resources.MR
import id.homebase.resources.feed_comment_encrypted
import id.homebase.resources.feed_view_all_comments
import org.jetbrains.compose.resources.stringResource

/** Emoji applied by the double-tap-to-like media gesture. */
private const val DOUBLE_TAP_EMOJI = "❤️"

/**
 * Aspect floor (width/height) for a single feed image: a portrait taller than this is
 * center-cropped to it so one post can't take over the scroll. 0.8 == a 4:5 frame (≈1350px at
 * 1080-wide = ~80% of the scrollable viewport, so the next post always peeks below). This is the
 * Instagram "portrait max 4:5" convention; since it equals the gallery's wide-image cap
 * [MomentMediaGallery.MaxFeedMediaAspect], every feed image renders as a uniform 4:5 card. The
 * full image is still shown uncropped in the detail/full-screen view.
 */
internal const val FeedMinMediaAspect = 0.8f

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
    embeddedAuthorName: String? = null,
    isPublic: Boolean = false,
    isOwnPost: Boolean = false,
    onEditPost: (() -> Unit)? = null,
    onDeletePost: (() -> Unit)? = null,
    onReportPost: (() -> Unit)? = null,
    onBlockAuthor: (() -> Unit)? = null,
    permission: CanReact? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(bottom = 4.dp),
    ) {
        val authorOdinId = post.authorOdinId
        if (authorOdinId != null) {
            PostAuthorHeader(
                authorOdinId = authorOdinId,
                displayName = displayName,
                channelName = channelName,
                // userDate (author's post time), not createdMs — createdMs is the local feed-drive
                // aggregation time for followed/public posts, which renders wrong dates (web parity:
                // Meta.tsx uses appData.userDate). createdMs still drives the timeline sort.
                timestampMs = post.userDateMs,
                onAuthorClick = onAuthorClick,
                modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp),
                isPublic = isPublic,
                isOwnPost = isOwnPost,
                // Only the author sees the audience: it's their own sharing choice, and on
                // someone else's post the ACL we hold is just our own copy's (web parity).
                audience = post.acl.toPostAudience().takeIf { isOwnPost },
                onEditPost = onEditPost,
                onDeletePost = onDeletePost,
                onReportPost = onReportPost,
                onBlockAuthor = onBlockAuthor,
            )
        }

        // When the caption is nothing but the URL the link card already represents, drop the bare
        // URL line so the card stands alone (Slack/X style). A URL embedded in real caption text is
        // kept — we don't silently edit the author's words.
        val hasLinkCard = post.payloads.any { it.key == FeedProtocol.LinksPayloadKey }
        val captionIsLoneUrl = post.caption.trim().let { c ->
            c.startsWith("http", ignoreCase = true) && c.none { it.isWhitespace() }
        }
        if (post.caption.isNotBlank() && !(hasLinkCard && captionIsLoneUrl)) {
            PostBody(
                caption = post.caption,
                onExpandFetchFullText = onExpandFetchFullText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        post.embeddedPost?.let { embedded ->
            QuotedPost(
                embedded = embedded,
                authorName = embeddedAuthorName,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // Link-preview card — only when the post carries a `pst_links` payload (mutually exclusive
        // with media: the sender saves one or the other). A bare URL with no payload stays plain text.
        PostLinkPreview(
            post = post,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

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
            permission = permission,
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
 * FB-style inline preview of the latest 1–3 comments + a "View all" affordance, read straight from
 * the post's [ReactionSummary.comments] (server-supplied with the header — no per-post fetch).
 * Each row and the link open the comments modal. Renders nothing when there are no comments.
 */
@Composable
private fun PostCommentPreview(
    summary: ReactionSummary?,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val encryptedLabel = stringResource(MR.string.feed_comment_encrypted)
    // Drop media-only / unparseable rows (blank body) rather than show an empty line.
    val shown = summary?.comments.orEmpty()
        .map { comment ->
            comment.odinId to if (comment.isEncrypted) encryptedLabel else comment.previewBody()
        }
        .filter { it.second.isNotBlank() }
        .takeLast(3)
    if (shown.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        shown.forEach { (odinId, body) ->
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(odinId) }
                    append("  ")
                    append(body)
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
    authorName: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            (authorName?.takeIf { it.isNotBlank() } ?: embedded.author?.takeIf { it.isNotBlank() })
                ?.let { author ->
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
 * Renders the post's saved link-preview (`pst_links`) payload as a [LinkPreviewCard], reusing the
 * chat receiver-side card. Renders nothing when the post has no such payload or the descriptor
 * can't be parsed — a bare URL in the caption with no preview payload just stays plain text (we
 * never fetch previews on the fly in the timeline). The card's image comes from the drive payload
 * (subject to the same over-peer fetch limits as media); the title/domain/description read straight
 * from the header descriptor, so they show even when the image can't load.
 */
@OptIn(ExperimentalEncodingApi::class)
@Composable
private fun PostLinkPreview(
    post: FeedPostItem,
    modifier: Modifier = Modifier,
) {
    val payload = post.payloads.firstOrNull { it.key == FeedProtocol.LinksPayloadKey } ?: return
    val descriptor = remember(payload.descriptorContent) {
        payload.descriptorContent?.let { content ->
            // Parse-tolerant: malformed/older descriptors yield null → no card, not a crash.
            runCatching {
                OdinSystemSerializer.deserialize<List<LinkPreviewDescriptor>>(content).firstOrNull()
            }.getOrNull()
        }
    } ?: return

    // Public feed posts ship the payload plaintext (no per-payload iv) — only encrypted posts carry
    // one. With an iv, decrypt the image with a payload-scoped key header; without, reuse the post's
    // own key header (the same one the media path uses for plaintext public posts). Either way the
    // descriptor text renders; this only governs the image fetch.
    val keyHeader = remember(payload.iv, post.keyHeader) {
        payload.iv
            ?.let { runCatching { Base64.decode(it) }.getOrNull() }
            ?.let { KeyHeader(it, post.keyHeader.aesKey) }
            ?: post.keyHeader
    }

    LinkPreviewCard(
        descriptor = descriptor,
        fileId = post.fileId,
        driveId = post.driveId,
        payloadKey = payload.key,
        keyHeader = keyHeader,
        previewThumbnail = payload.previewThumbnail?.toEmbeddedThumb(),
        modifier = modifier,
    )
}

/**
 * The post's media payloads (key prefix [FeedProtocol.MediaPayloadKeyPrefix]) rendered
 * through the feed-shaped [MomentMediaGallery] — aspect-driven, full-width, with an
 * Instagram-style carousel for 2+. Translates the gallery's payload-keyed click into the
 * card's 0-based [onMediaClick] index and forwards the double-tap-to-like gesture.
 * Renders nothing when the post has no media payloads.
 *
 * A followed identity's media payloads live on the author's drive, not our local feed drive, so for
 * a received post (non-null [FeedPostItem.senderOdinId]) the gallery reads them **over peer** by
 * [FeedPostItem.globalTransitId] from the author's channel drive ([FeedPostItem.channelId]); our own
 * posts (null senderOdinId) stay on the local [FeedPostItem.driveId]. See
 * reference_over_peer_media_v2_route / PeerFileByGlobalTransitProvider.
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

    // Route peer-authored media over-peer. senderOdinId is non-null only on a received (feed-drive)
    // copy — our own posts stay local. The peer read targets the author's CHANNEL drive
    // (post.channelId, a drive-alias GUID) keyed by globalTransitId, NOT the local feed drive.
    val peerGtid = post.globalTransitId
    val channelDriveAlias = runCatching { Uuid.parse(post.channelId) }.getOrNull()
    val isPeerMedia = post.senderOdinId != null && peerGtid != null && channelDriveAlias != null

    if (mediaPayloads.isEmpty()) return

    val mediaDriveId = channelDriveAlias?.takeIf { isPeerMedia } ?: post.driveId
    val mediaRemoteOdinId = post.senderOdinId?.takeIf { isPeerMedia }
    val mediaGlobalTransitId = peerGtid?.takeIf { isPeerMedia }

    // Each double-tap bumps the tick so [DoubleTapHeartBurst] replays its pop-and-fade ❤️.
    var burstTick by remember { mutableIntStateOf(0) }

    Box(modifier = modifier) {
        MomentMediaGallery(
            payloads = mediaPayloads,
            fileId = post.fileId,
            driveId = mediaDriveId,
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
            minAspect = FeedMinMediaAspect,
            remoteOdinId = mediaRemoteOdinId,
            globalTransitId = mediaGlobalTransitId,
        )
        DoubleTapHeartBurst(tick = burstTick)
    }
}

/**
 * The signature like gesture: a big ❤️ that springs up with an overshoot and fades, centred over
 * the media — replayed whenever [tick] changes (each double-tap). Renders nothing before the first
 * tap. Purely decorative; the actual reaction is fired by the caller.
 *
 * A faint dark scrim heart sits one layer behind the white one so the burst stays legible over
 * both bright and dark media (the universal IG-style "white heart over photo" — the white tint is
 * deliberately not a theme role since the media beneath it is arbitrary).
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
    // Shadow heart (slightly larger, dark, low alpha) for legibility on light media.
    Icon(
        imageVector = Icons.Filled.Favorite,
        contentDescription = null,
        tint = Color.Black.copy(alpha = 0.25f),
        modifier = Modifier
            .align(Alignment.Center)
            .size(110.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
            },
    )
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
