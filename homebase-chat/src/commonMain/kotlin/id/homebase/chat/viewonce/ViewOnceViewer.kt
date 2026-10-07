package id.homebase.chat.viewonce

import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.ui.input.pointer.pointerInput
import id.homebase.resources.chat_view_once_mute
import id.homebase.resources.chat_view_once_remaining
import kotlinx.coroutines.delay
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import id.homebase.api.util.markdownToPlainPreview
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.cd_view_once_toggle
import id.homebase.resources.chat_view_once_retry
import id.homebase.resources.chat_view_once_viewer_failed_body
import id.homebase.resources.chat_view_once_viewer_failed_title
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
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import id.homebase.core.util.SecureWindowEffect
import id.homebase.core.util.rememberScreenCaptureObserver
import id.homebase.resources.chat_view_once_capture_blocked
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
const val VIEW_ONCE_VIEWER_BLOCKED_TAG = "viewOnceViewerBlocked"
const val VIEW_ONCE_VIEWER_MUTE_TAG = "viewOnceViewerMute"
const val VIEW_ONCE_VIEWER_CAPTION_TAG = "viewOnceViewerCaption"
internal const val VIEW_ONCE_CHROME_HIDE_MS = 2_500L

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
    onScreenshot: () -> Unit = {},
    loader: ViewOncePayloadLoader = koinInject(),
    captureObserver: @Composable (onScreenshot: () -> Unit) -> State<Boolean> = { rememberScreenCaptureObserver(it) },
) {
    SecureWindowEffect(active = true)
    val screenCaptured by captureObserver(onScreenshot)
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

    var muted by remember(data.messageId) { mutableStateOf(false) }
    ViewOnceViewerFrame(
        isVideo = isVideo,
        caption = remember(data.caption) { data.caption?.let { markdownToPlainPreview(it, ViewOnceDescriptor.MAX_CAPTION_CODEPOINTS) }?.takeIf { it.isNotBlank() } },
        mediaShown = shown && !failed,
        failed = failed,
        positionMs = { positionMs },
        durationMs = durationMs,
        muted = muted,
        onMutedChange = { muted = it },
        onClose = ::close,
        modifier = modifier,
    ) { belowHeader ->
        when {
            screenCaptured -> ViewOnceCaptureBlocked(belowHeader)
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
                    muted = muted,
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

/**
 * Always dark, like the camera: media reads best on black, and the chrome must not flip with the app theme.
 * Once the media is on screen the chrome steps aside after a moment and a tap brings it back; while loading
 * or failed it stays, because those states are nothing but chrome.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ViewOnceViewerFrame(
    isVideo: Boolean,
    caption: String? = null,
    mediaShown: Boolean,
    failed: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    positionMs: () -> Long = { 0L },
    durationMs: Long? = null,
    muted: Boolean = false,
    onMutedChange: ((Boolean) -> Unit)? = null,
    body: @Composable BoxScope.(belowHeader: Modifier) -> Unit,
) {
    HomebaseTheme(darkTheme = true, followsSystemTheme = false, updatesSystemChrome = false) {
        val density = LocalDensity.current
        val motion = MaterialTheme.motionScheme
        var headerHeight by remember { mutableStateOf(0.dp) }
        var chromeRequested by remember { mutableStateOf(true) }
        val chromeVisible = chromeRequested || !mediaShown
        LaunchedEffect(mediaShown, chromeRequested) {
            if (mediaShown && chromeRequested) {
                delay(VIEW_ONCE_CHROME_HIDE_MS)
                chromeRequested = false
            }
        }
        Box(
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim)
                .pointerInput(mediaShown) { detectTapGestures { if (mediaShown) chromeRequested = !chromeRequested } },
        ) {
            body(Modifier.fillMaxSize().padding(top = headerHeight))
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { -it / 4 },
                exit = fadeOut(motion.defaultEffectsSpec()) + slideOutVertically(motion.defaultSpatialSpec()) { -it / 4 },
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                ViewOnceViewerHeader(
                    onClose = onClose,
                    modifier = Modifier.onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
                )
            }
            val shownCaption = caption.takeIf { mediaShown }
            val hasFooter = shownCaption != null || (isVideo && mediaShown)
            AnimatedVisibility(
                visible = (chromeVisible || shownCaption != null) && !failed && hasFooter,
                enter = fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { it / 4 },
                exit = fadeOut(motion.defaultEffectsSpec()) + slideOutVertically(motion.defaultSpatialSpec()) { it / 4 },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ViewOnceViewerFooter(
                    caption = shownCaption,
                    positionMs = positionMs.takeIf { isVideo && mediaShown && chromeVisible },
                    durationMs = durationMs,
                    muted = muted,
                    onMutedChange = onMutedChange?.takeIf { isVideo && mediaShown && chromeVisible },
                )
            }
        }
    }
}

// Translucent so the media reads through the chrome, the way a camera's controls float over the frame.
@Composable
private fun chromeContainer(): Color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = CHROME_ALPHA)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ViewOnceViewerHeader(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(colors.scrim.copy(alpha = 0.6f), colors.scrim.copy(alpha = 0f))))
            .statusBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp, bottom = 32.dp),
    ) {
        FilledTonalIconButton(
            onClick = onClose,
            shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = chromeContainer(),
                contentColor = colors.onSurface,
            ),
            modifier = Modifier.align(Alignment.CenterStart).size(CHROME_SIZE).testTag(VIEW_ONCE_VIEWER_CLOSE_TAG),
        ) {
            Icon(Icons.Default.Close, contentDescription = stringResource(MR.string.chat_view_once_close))
        }
        Box(
            Modifier.align(Alignment.Center).size(CHROME_SIZE).clip(CircleShape).background(chromeContainer()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(ViewOnceIcon, contentDescription = stringResource(MR.string.cd_view_once_toggle), tint = colors.onSurface, modifier = Modifier.size(28.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ViewOnceViewerFooter(
    caption: String?,
    positionMs: (() -> Long)?,
    durationMs: Long?,
    muted: Boolean,
    onMutedChange: ((Boolean) -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(colors.scrim.copy(alpha = 0f), colors.scrim.copy(alpha = 0.6f))))
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 12.dp, top = 40.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (positionMs != null || onMutedChange != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (positionMs != null && durationMs != null && durationMs > 0) {
                    // Read-only: a view-once video can't be scrubbed, but the reader can see how much is left.
                    // Position arrives about twice a second; easing between ticks keeps the wave moving steadily.
                    val position = positionMs().coerceIn(0L, durationMs)
                    val smooth by animateFloatAsState(position.toFloat() / durationMs, tween(POSITION_TICK_MS, easing = LinearEasing))
                    LinearWavyProgressIndicator(
                        progress = { smooth },
                        modifier = Modifier.weight(1f).testTag(VIEW_ONCE_VIEWER_PROGRESS_TAG),
                        color = colors.primary,
                        trackColor = colors.onSurface.copy(alpha = 0.24f),
                    )
                    Text(
                        text = stringResource(MR.string.chat_view_once_remaining, formatClock(durationMs - position)),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (onMutedChange != null) {
                    FilledTonalIconToggleButton(
                        checked = muted,
                        onCheckedChange = onMutedChange,
                        shapes = IconButtonDefaults.toggleableShapes(),
                        colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                            containerColor = chromeContainer(),
                            contentColor = colors.onSurface,
                            checkedContainerColor = colors.inverseSurface,
                            checkedContentColor = colors.inverseOnSurface,
                        ),
                        modifier = Modifier.padding(start = 8.dp).size(CHROME_SIZE).testTag(VIEW_ONCE_VIEWER_MUTE_TAG),
                    ) {
                        Icon(
                            if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = stringResource(MR.string.chat_view_once_mute),
                        )
                    }
                }
            }
        }
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                color = colors.onSurface,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag(VIEW_ONCE_VIEWER_CAPTION_TAG),
            )
        }
    }
}

private fun formatClock(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) + 999L) / 1000L
    val seconds = totalSeconds % 60
    return "${totalSeconds / 60}:${if (seconds < 10) "0" else ""}$seconds"
}

@Composable
internal fun ViewOnceCaptureBlocked(modifier: Modifier = Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surface).testTag(VIEW_ONCE_VIEWER_BLOCKED_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(MR.string.chat_view_once_capture_blocked),
            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ViewOnceViewerLoading(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        ViewOnceLoadingIndicator(64.dp)
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
private const val CHROME_ALPHA = 0.55f
private val CHROME_SIZE = 48.dp

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
