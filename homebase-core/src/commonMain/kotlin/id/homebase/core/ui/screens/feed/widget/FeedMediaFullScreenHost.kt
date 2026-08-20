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

// A SharedTransitionLayout + AnimatedContent that SWAPS the screen for the viewer, which is what supplies the
// scopes the shared chat viewers require. Because the swap disposes [content], any scroll state that must
// survive a dismiss has to be hoisted above this host.
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

                // Share / save / delete need a feed-side action service to decrypt a payload to a file; until
                // that exists no handler is passed, so those controls stay hidden rather than dead.
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

// Drive resolution mirrors PostMedia: a received post (senderOdinId + globalTransitId + a parseable channel
// alias) points at the author's channel drive over peer; our own posts stay local on [FeedPostItem.driveId].
// The image viewer honours those peer fields; the video player does not yet — peer video playback still issues
// a local read and falls back to its poster frame.
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
