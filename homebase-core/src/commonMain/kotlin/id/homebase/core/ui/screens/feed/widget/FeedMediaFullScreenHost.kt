package id.homebase.core.ui.screens.feed.widget

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.widget.FullScreenMediaViewer
import id.homebase.chat.widget.FullScreenVideoPlayer
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.FeedProtocol
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Wraps a feed screen so a tapped photo/video can take over the whole window, reusing the shared
 * chat viewers ([FullScreenMediaViewer] / [FullScreenVideoPlayer]) rather than a feed-specific one.
 *
 * Same shape as `MomentDetailScreen`'s host: a [SharedTransitionLayout] + [AnimatedContent] that
 * *swaps* the screen for the viewer, which is what supplies the `sharedTransitionScope` /
 * `animatedVisibilityScope` the viewers require. Because the swap disposes [content], any scroll
 * state the caller wants to survive a dismiss has to be hoisted above this host — both feed screens
 * do that.
 *
 * [overlay] is screen-local state owned by the caller; null renders [content] unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
internal fun FeedMediaFullScreenHost(
    overlay: FullScreenOverlay?,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = overlay,
            contentKey = { target ->
                when (target) {
                    null -> "feed"
                    is FullScreenOverlay.ViewMessageData -> "image"
                    is FullScreenOverlay.VideoPlayerData -> "video"
                    is FullScreenOverlay.AttachmentData -> "attachment"
                    is FullScreenOverlay.PdfViewerData -> "pdf"
                }
            },
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
        ) { target ->
            when (target) {
                null -> content()

                // Share / save / delete need a feed-side action service to decrypt a payload to a
                // file; until that exists no handler is passed, so those controls stay hidden
                // rather than rendering as dead buttons.
                is FullScreenOverlay.ViewMessageData -> FullScreenMediaViewer(
                    data = target,
                    onDismiss = onDismiss,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedContent,
                )

                is FullScreenOverlay.VideoPlayerData -> FullScreenVideoPlayer(
                    data = target,
                    onDismiss = onDismiss,
                    uploadStatus = null,
                )

                // Chat-composer / PDF overlays; [feedMediaOverlay] never produces them.
                is FullScreenOverlay.AttachmentData -> Unit
                is FullScreenOverlay.PdfViewerData -> Unit
            }
        }
    }
}

/**
 * The overlay for the media payload at [index] of [post] (the 0-based index `PostCard`'s
 * `onMediaClick` reports), or null when the post has no payload there. A video payload routes to
 * the video player and everything else to the image viewer — the same split Moments makes.
 *
 * The image viewer is handed the post's **whole** media list plus the tapped key, so its pager can
 * swipe across a multi-image post from wherever the tap landed.
 *
 * Drive resolution mirrors `PostMedia`: a followed identity's payload bytes live on the author's
 * channel drive, not our feed drive, so a received post (a `senderOdinId` plus a `globalTransitId`
 * and a parseable channel alias) points at the channel drive, addressed over peer by
 * `globalTransitId`; our own posts stay local on [FeedPostItem.driveId]. The image viewer honours
 * those peer fields; the video player does not yet — peer video playback still issues a local read
 * and falls back to its poster frame.
 *
 * @param title shown in the viewer's app bar; callers pass the resolved author name.
 */
internal fun feedMediaOverlay(
    post: FeedPostItem,
    index: Int,
    title: String,
): FullScreenOverlay? {
    val mediaPayloads = post.payloads
        .filter { it.key.startsWith(FeedProtocol.MediaPayloadKeyPrefix) }
    val payload = mediaPayloads.getOrNull(index) ?: return null

    val channelDriveAlias = runCatching { Uuid.parse(post.channelId) }.getOrNull()
    val isPeerMedia = post.senderOdinId != null &&
        post.globalTransitId != null &&
        channelDriveAlias != null
    val driveId = channelDriveAlias?.takeIf { isPeerMedia } ?: post.driveId

    val contentType = payload.contentType.orEmpty()
    val isVideo = contentType.startsWith("video/") ||
        contentType == "application/vnd.apple.mpegurl"

    return if (isVideo) {
        FullScreenOverlay.VideoPlayerData(
            fileId = post.fileId,
            driveId = driveId,
            payloadKey = payload.key,
            keyHeader = post.keyHeader,
            payload = payload,
            isEncrypted = post.isEncrypted,
        )
    } else {
        FullScreenOverlay.ViewMessageData(
            messageId = post.id,
            title = title,
            userDate = Instant.fromEpochMilliseconds(post.userDateMs),
            content = post.caption,
            fileId = post.fileId,
            driveId = driveId,
            payloads = mediaPayloads,
            keyHeader = post.keyHeader,
            selectedPayloadKey = payload.key,
            isEncrypted = post.isEncrypted,
            remoteOdinId = post.senderOdinId?.takeIf { isPeerMedia },
            globalTransitId = post.globalTransitId?.takeIf { isPeerMedia },
        )
    }
}
