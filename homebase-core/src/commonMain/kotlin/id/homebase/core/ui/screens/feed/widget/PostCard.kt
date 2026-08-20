package id.homebase.core.ui.screens.feed.widget

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
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
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.builder.LinkPreviewDescriptor
import id.homebase.chat.widget.LinkPreviewCard
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.PublicAvatar
import id.homebase.core.feed.services.CanReact
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.authorOdinId
import id.homebase.core.feed.services.toPostAudience
import id.homebase.core.feed.services.previewBody
import id.homebase.core.ui.screens.moments.widget.MomentMediaGallery
import id.homebase.core.util.formatTimestamp
import id.homebase.core.util.getUriHandler
import id.homebase.core.util.initials
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
import kotlin.time.Instant
import id.homebase.resources.MR
import id.homebase.resources.feed_comment_encrypted
import id.homebase.resources.feed_view_all_comments
import org.jetbrains.compose.resources.stringResource

private const val DOUBLE_TAP_EMOJI = "❤️"

// Aspect floor (width/height) for a single feed image: 0.8 == a 4:5 frame, the Instagram "portrait max 4:5"
// convention, so the next post always peeks below. Equals MomentMediaGallery.MaxFeedMediaAspect, so every feed
// image renders as a uniform 4:5 card; the detail view still shows the image uncropped.
internal const val FeedMinMediaAspect = 0.8f

// Deliberately NOT a Material Card: each post is a flat full-bleed band on `surface`, with the list painting
// the gap between posts.
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
                // userDate (the author's post time), not createdMs — createdMs is the local aggregation time
                // for followed posts and renders wrong dates. createdMs still drives the timeline sort.
                timestampMs = post.userDateMs,
                onAuthorClick = onAuthorClick,
                modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp),
                isPublic = isPublic,
                isOwnPost = isOwnPost,
                // Only the author sees the audience: on someone else's post the ACL we hold is just our copy's.
                audience = post.acl.toPostAudience().takeIf { isOwnPost },
                onEditPost = onEditPost,
                onDeletePost = onDeletePost,
                onReportPost = onReportPost,
                onBlockAuthor = onBlockAuthor,
            )
        }

        // Drop a caption that is nothing but the URL the link card already shows; a URL embedded in real
        // caption text is kept — we don't silently edit the author's words.
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

        // Mutually exclusive with media: the sender saves one or the other.
        PostLinkPreview(
            post = post,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        // Edge-to-edge — no horizontal padding, no corner clip.
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

// Read straight from the header's ReactionSummary.comments — server-supplied, no per-post fetch.
@Composable
private fun PostCommentPreview(
    summary: ReactionSummary?,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val encryptedLabel = stringResource(MR.string.feed_comment_encrypted)
    // Drop media-only / unparseable rows rather than show an empty line.
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

// Renders nothing when the embed yields no author, caption or media — a repost of a caption-less photo post
// otherwise landed here as an empty bordered box.
@Composable
private fun QuotedPost(
    embedded: EmbeddedPost,
    authorName: String?,
    modifier: Modifier = Modifier,
) {
    // Wire value, unvalidated: OdinId's constructor throws on a non-domain, which inside a LazyColumn item
    // would take the whole timeline down.
    val author = remember(embedded.authorOdinId) {
        embedded.authorOdinId?.takeIf { OdinId.isValid(it) }?.let { OdinId(it) }
    }
    val caption = embedded.caption?.trim().orEmpty()
    val mediaPayloads = remember(embedded.payloads) {
        embedded.payloads.orEmpty().filter { it.key.startsWith(FeedProtocol.MediaPayloadKeyPrefix) }
    }
    if (author == null && caption.isEmpty() && mediaPayloads.isEmpty()) return

    val uriHandler = getUriHandler()
    val permalink = embedded.permalink?.takeIf { it.startsWith("http", ignoreCase = true) }
    val openSource = permalink?.let { link -> { uriHandler.openUrl(link) } }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = if (openSource != null) Modifier.clickable(onClick = openSource) else Modifier,
        ) {
            if (author != null || caption.isNotEmpty()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    if (author != null) {
                        val name = authorName?.takeIf { it.isNotBlank() } ?: author.domainName
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PublicAvatar(
                                odinId = author,
                                initials = name.initials(),
                                options = AvatarOptions(size = 28.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = name,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            embedded.userDate?.takeIf { it > 0L }?.let { ms ->
                                VerticalDivider(
                                    modifier = Modifier
                                        .padding(horizontal = 8.dp)
                                        .height(12.dp),
                                    thickness = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                                Text(
                                    text = formatTimestamp(Instant.fromEpochMilliseconds(ms)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    if (caption.isNotEmpty()) {
                        Text(
                            text = caption,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = if (author != null) 6.dp else 0.dp),
                        )
                    }
                }
            }
            QuotedPostMedia(
                embedded = embedded,
                payloads = mediaPayloads,
                author = author,
                onClick = openSource,
            )
        }
    }
}

// The quoted post's bytes live on the SOURCE author's drive — a third identity — so this reads over peer by
// the embed's globalTransitId. Older/trimmed embeds carry no payload list at all.
@Composable
private fun QuotedPostMedia(
    embedded: EmbeddedPost,
    payloads: List<PayloadDescriptor>,
    author: OdinId?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (payloads.isEmpty() || author == null) return
    val fileId = remember(embedded.fileId) {
        embedded.fileId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    } ?: return
    val driveId = remember(embedded.channelId) {
        embedded.channelId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    } ?: return
    val globalTransitId = remember(embedded.globalTransitId) {
        embedded.globalTransitId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    } ?: return
    val thumb = remember(embedded.previewThumbnail) {
        embedded.previewThumbnail?.let { element ->
            runCatching {
                OdinSystemSerializer.json.decodeFromJsonElement(EmbeddedThumb.serializer(), element)
            }.getOrNull()
        }
    }

    MomentMediaGallery(
        payloads = payloads,
        fileId = fileId,
        driveId = driveId,
        previewThumbnail = thumb,
        // Repost is only offered on public (unencrypted) posts, so these payloads carry no per-payload iv.
        keyHeader = KeyHeader.empty(),
        modifier = modifier.fillMaxWidth(),
        onMediaClick = onClick?.let { open -> { _: PayloadDescriptor -> open() } },
        sharedTransitionScope = null,
        animatedVisibilityScope = null,
        messageId = fileId,
        downloadingFiles = emptySet(),
        minAspect = FeedMinMediaAspect,
        remoteOdinId = author,
        globalTransitId = globalTransitId,
    )
}

// Parse-tolerant by design: a bare URL with no preview payload stays plain text — previews are never fetched
// on the fly in the timeline. Title/domain/description come off the header, so they show even with no image.
@OptIn(ExperimentalEncodingApi::class)
@Composable
private fun PostLinkPreview(
    post: FeedPostItem,
    modifier: Modifier = Modifier,
) {
    val payload = post.payloads.firstOrNull { it.key == FeedProtocol.LinksPayloadKey } ?: return
    val descriptor = remember(payload.descriptorContent) {
        payload.descriptorContent?.let { content ->
            runCatching {
                OdinSystemSerializer.deserialize<List<LinkPreviewDescriptor>>(content).firstOrNull()
            }.getOrNull()
        }
    } ?: return

    // Public posts ship the payload plaintext (no per-payload iv); only encrypted posts carry one. This
    // governs the image fetch only — the descriptor text renders either way.
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

// A followed identity's media lives on the author's drive, so a received post (non-null senderOdinId) reads
// over peer by globalTransitId from the author's channel drive; our own posts stay on the local driveId.
@Composable
private fun PostMedia(
    post: FeedPostItem,
    onMediaClick: (index: Int) -> Unit,
    onDoubleTapLike: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mediaPayloads: List<PayloadDescriptor> =
        post.payloads.filter { it.key.startsWith(FeedProtocol.MediaPayloadKeyPrefix) }

    // The peer read targets the author's CHANNEL drive (post.channelId), NOT the local feed drive.
    val peerGtid = post.globalTransitId
    val channelDriveAlias = runCatching { Uuid.parse(post.channelId) }.getOrNull()
    val isPeerMedia = post.senderOdinId != null && peerGtid != null && channelDriveAlias != null

    if (mediaPayloads.isEmpty()) return

    val mediaDriveId = channelDriveAlias?.takeIf { isPeerMedia } ?: post.driveId
    val mediaRemoteOdinId = post.senderOdinId?.takeIf { isPeerMedia }
    val mediaGlobalTransitId = peerGtid?.takeIf { isPeerMedia }

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

// The white tint is deliberately not a theme role — the media beneath it is arbitrary.
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
    // Shadow heart for legibility on light media.
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
