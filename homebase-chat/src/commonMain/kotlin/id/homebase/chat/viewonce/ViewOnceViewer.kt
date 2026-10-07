package id.homebase.chat.viewonce

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import co.touchlab.kermit.Logger
import id.homebase.api.image.toImageBitmap
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.widget.video.VideoPlayerSurface
import id.homebase.core.config.chatTargetDrive
import id.homebase.resources.MR
import id.homebase.resources.chat_message_image_attachment
import id.homebase.resources.chat_view_once_close
import id.homebase.resources.chat_view_once_failed
import id.homebase.resources.chat_view_once_viewer_hint_photo
import id.homebase.resources.chat_view_once_viewer_hint_video
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import androidx.compose.ui.backhandler.BackHandler

const val VIEW_ONCE_VIEWER_CLOSE_TAG = "viewOnceViewerClose"
const val VIEW_ONCE_VIEWER_RETRY_TAG = "viewOnceViewerRetry"
const val VIEW_ONCE_VIEWER_IMAGE_TAG = "viewOnceViewerImage"

/**
 * Full-screen viewer for one received view-once item. It has no save, share, forward or paging,
 * and it reads the payload only through [loader]. However it ends (close button, back, the app
 * leaving the foreground, or leaving composition) [onViewerClosed] runs exactly once, and only
 * if the media was actually shown: an item that never loaded is not used up.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ViewOnceViewer(
    data: FullScreenOverlay.ViewOnceViewer,
    onViewerClosed: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    loader: ViewOncePayloadLoader = koinInject(),
) {
    val isVideo = data.kind == ViewOnceDescriptor.KIND_VIDEO
    var image by remember(data.messageId) { mutableStateOf<ImageBitmap?>(null) }
    var videoShown by remember(data.messageId) { mutableStateOf(false) }
    var failed by remember(data.messageId) { mutableStateOf(false) }
    var videoReady by remember(data.messageId) { mutableStateOf(false) }
    var attempt by remember(data.messageId) { mutableIntStateOf(0) }
    val hold = remember(data.messageId) { CloseOnce() }
    val latestOnDismiss by rememberUpdatedState(onDismiss)

    LaunchedEffect(data.messageId, attempt) {
        failed = false
        try {
            if (hold.claim()) {
                try {
                    loader.begin(data.fileId)
                } catch (e: Throwable) {
                    hold.release()
                    throw e
                }
            }
            if (isVideo) {
                videoReady = true
            } else {
                val bytes = loader.loadBytes(chatTargetDrive.alias, data.fileId, data.payload.key, data.keyHeader)
                val bitmap = withContext(Dispatchers.Default) { bytes.toImageBitmap() }
                if (bitmap == null) failed = true else image = bitmap
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ViewOnceAlreadyConsumedException) {
            latestOnDismiss()
        } catch (e: Exception) {
            Logger.w("ViewOnceViewer", e) { "load failed msg=${data.messageId}" }
            failed = true
        }
    }

    val shown by rememberUpdatedState(image != null || videoShown)
    val latestOnClosed by rememberUpdatedState(onViewerClosed)
    val closeGuard = remember(data.messageId) { CloseOnce() }
    fun consumeOnce() {
        if (closeGuard.claim() && shown) {
            loader.markConsumed(data.fileId)
            latestOnClosed()
        }
    }

    DisposableEffect(data.messageId) {
        onDispose {
            consumeOnce()
            // Only a viewer that took the hold may release it, or it would unmark a live sibling's file.
            if (hold.isClaimed) loader.evictAsync(chatTargetDrive.alias, data.fileId, data.payload.key)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        consumeOnce()
        onDismiss()
    }
    fun close() {
        consumeOnce()
        onDismiss()
    }
    @Suppress("DEPRECATION")
    BackHandler(enabled = true) { close() }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim)) {
        when {
            failed -> Box(
                Modifier.fillMaxSize().testTag(VIEW_ONCE_VIEWER_RETRY_TAG).clickable { attempt++ },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(MR.string.chat_view_once_failed),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }
            isVideo && videoReady -> VideoPlayerSurface(
                data = FullScreenOverlay.VideoPlayerData(
                    fileId = data.fileId,
                    driveId = chatTargetDrive.alias,
                    payloadKey = data.payload.key,
                    keyHeader = data.keyHeader,
                    payload = data.payload,
                ),
                modifier = Modifier.fillMaxSize(),
                onFirstFrame = { videoShown = true },
                onError = { failed = true },
            )
            image != null -> ZoomableBitmap(image!!)
            else -> CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.6f), CircleShape)
                    .padding(12.dp),
                color = MaterialTheme.colorScheme.inverseOnSurface,
            )
        }

        IconButton(
            onClick = ::close,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.6f),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
                .testTag(VIEW_ONCE_VIEWER_CLOSE_TAG),
        ) {
            Icon(Icons.Default.Close, contentDescription = stringResource(MR.string.chat_view_once_close))
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(
                    if (isVideo) MR.string.chat_view_once_viewer_hint_video else MR.string.chat_view_once_viewer_hint_photo,
                ),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.6f), CircleShape)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ZoomableBitmap(bitmap: ImageBitmap) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale <= 1f) Offset.Zero else offset + pan
    }
    Image(
        bitmap = bitmap,
        contentDescription = stringResource(MR.string.chat_message_image_attachment),
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .testTag(VIEW_ONCE_VIEWER_IMAGE_TAG)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .transformable(transformState),
    )
}

private class CloseOnce {
    private var done = false

    val isClaimed: Boolean get() = done

    fun claim(): Boolean {
        if (done) return false
        done = true
        return true
    }

    fun release() {
        done = false
    }
}
