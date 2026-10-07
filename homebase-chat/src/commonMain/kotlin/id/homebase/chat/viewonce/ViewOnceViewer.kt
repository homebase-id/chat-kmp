package id.homebase.chat.viewonce

import androidx.compose.foundation.Image
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.chat_view_once_retry
import id.homebase.resources.chat_view_once_viewer_failed_body
import id.homebase.resources.chat_view_once_viewer_failed_title
import id.homebase.resources.chat_view_once_viewer_title_photo
import id.homebase.resources.chat_view_once_viewer_title_video
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.ui.text.style.LineBreak
import id.homebase.api.client.drives.files.DescriptorContent
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
const val VIEW_ONCE_VIEWER_PROGRESS_TAG = "viewOnceViewerProgress"

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
    var positionMs by remember(data.messageId) { mutableLongStateOf(0L) }
    val durationMs = remember(data.payload) { (data.payload.descriptorInfo() as? DescriptorContent.VideoFile)?.durationMs }
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

    val playback = durationMs?.takeIf { isVideo && videoShown && !failed }?.let { total ->
        { (positionMs.toFloat() / total).coerceIn(0f, 1f) }
    }
    ViewOnceViewerFrame(isVideo = isVideo, playbackProgress = playback, onClose = ::close, modifier = modifier) { belowHeader ->
        when {
            failed -> ViewOnceViewerFailed(onRetry = { attempt++ }, modifier = belowHeader)
            isVideo && videoReady -> {
                VideoPlayerSurface(
                    data = FullScreenOverlay.VideoPlayerData(
                        fileId = data.fileId,
                        driveId = chatTargetDrive.alias,
                        payloadKey = data.payload.key,
                        keyHeader = data.keyHeader,
                        payload = data.payload,
                    ),
                    modifier = Modifier.fillMaxSize(),
                    onFirstFrame = { videoShown = true },
                    onPositionUpdate = { positionMs = it },
                    onError = { failed = true },
                )
                if (!videoShown) ViewOnceViewerLoading(belowHeader)
            }
            image != null -> ViewOnceViewerImage(image!!)
            else -> ViewOnceViewerLoading(belowHeader)
        }
    }
}

/** Always dark, like the camera: media reads best on black, and the chrome must not flip with the app theme. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ViewOnceViewerFrame(
    isVideo: Boolean,
    playbackProgress: (() -> Float)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    body: @Composable BoxScope.(belowHeader: Modifier) -> Unit,
) {
    HomebaseTheme(darkTheme = true, followsSystemTheme = false, updatesSystemChrome = false) {
        val density = LocalDensity.current
        var headerHeight by remember { mutableStateOf(0.dp) }
        Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim)) {
            body(Modifier.fillMaxSize().padding(top = headerHeight))
            ViewOnceViewerHeader(
                isVideo = isVideo,
                playbackProgress = playbackProgress,
                onClose = onClose,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ViewOnceViewerHeader(
    isVideo: Boolean,
    playbackProgress: (() -> Float)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(colors.scrim.copy(alpha = 0.8f), colors.scrim.copy(alpha = 0f))))
            .statusBarsPadding()
            .padding(bottom = 32.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 20.dp, top = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            IconButton(
                onClick = onClose,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = colors.surfaceContainerHighest.copy(alpha = 0.72f),
                    contentColor = colors.onSurface,
                ),
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.size(48.dp).testTag(VIEW_ONCE_VIEWER_CLOSE_TAG),
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(MR.string.chat_view_once_close))
            }
            Box(
                Modifier.padding(start = 12.dp, top = 6.dp).size(36.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(colors.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(ViewOnceDigitIcon, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(26.dp))
            }
            Column(
                Modifier.padding(start = 12.dp).heightIn(min = 48.dp).weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            ) {
                Text(
                    text = stringResource(if (isVideo) MR.string.chat_view_once_viewer_title_video else MR.string.chat_view_once_viewer_title_photo),
                    style = MaterialTheme.typography.titleMediumEmphasized.copy(textDirection = TextDirection.Content),
                    color = colors.onSurface,
                )
                // Shown from the first frame, so the header never shifts and the reader knows before anything loads.
                Text(
                    text = stringResource(
                        if (isVideo) MR.string.chat_view_once_viewer_hint_video else MR.string.chat_view_once_viewer_hint_photo,
                    ),
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                    color = colors.onSurfaceVariant,
                )
            }
        }
        // Read-only: a view-once video can't be scrubbed, but the reader can see how much is left.
        if (playbackProgress != null) {
            // Position arrives about twice a second; easing between ticks keeps the wave moving steadily.
            val smooth by animateFloatAsState(playbackProgress(), tween(POSITION_TICK_MS, easing = LinearEasing))
            LinearWavyProgressIndicator(
                progress = { smooth },
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp).testTag(VIEW_ONCE_VIEWER_PROGRESS_TAG),
                color = colors.primary,
                trackColor = colors.onSurface.copy(alpha = 0.24f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ViewOnceViewerLoading(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        ViewOnceLoadingIndicator(Modifier.size(64.dp))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ViewOnceViewerFailed(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(72.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(colors.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(32.dp))
        }
        Text(
            text = stringResource(MR.string.chat_view_once_viewer_failed_title),
            style = MaterialTheme.typography.headlineSmallEmphasized,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            text = stringResource(MR.string.chat_view_once_viewer_failed_body),
            style = MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Paragraph),
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp).widthIn(max = 320.dp),
        )
        Button(
            onClick = onRetry,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.padding(top = 24.dp).heightIn(min = 48.dp).testTag(VIEW_ONCE_VIEWER_RETRY_TAG),
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(MR.string.chat_view_once_retry))
        }
    }
}

@Composable
internal fun ViewOnceViewerImage(bitmap: ImageBitmap) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val reveal = remember { Animatable(0f) }
    val revealSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) { reveal.animateTo(1f, revealSpec) }
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
                val entry = 0.92f + 0.08f * reveal.value
                alpha = reveal.value.coerceIn(0f, 1f)
                scaleX = scale * entry
                scaleY = scale * entry
                translationX = offset.x
                translationY = offset.y
            }
            .transformable(transformState),
    )
}

private const val POSITION_TICK_MS = 500

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
