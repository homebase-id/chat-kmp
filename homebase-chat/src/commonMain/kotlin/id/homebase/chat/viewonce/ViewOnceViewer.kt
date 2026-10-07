package id.homebase.chat.viewonce

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.file.AppCacheDirs
import id.homebase.api.util.markdownToPlainPreview
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.widget.video.VideoPlayerSurface
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.media.subsample.SubSamplingImageSource
import id.homebase.core.media.subsample.ZoomableSubSamplingImage
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.ui.theme.withEmojiFont
import id.homebase.core.widget.quickReactions
import id.homebase.core.util.SecureWindowEffect
import id.homebase.core.util.formatTimestamp
import id.homebase.core.util.rememberScreenCaptureObserver
import id.homebase.resources.MR
import id.homebase.resources.cd_view_once_toggle
import id.homebase.resources.chat_message_emoji_options
import id.homebase.resources.chat_message_image_attachment
import id.homebase.resources.chat_message_reply
import id.homebase.resources.chat_view_once_capture_blocked
import id.homebase.resources.chat_view_once_close
import id.homebase.resources.chat_view_once_pause
import id.homebase.resources.chat_view_once_play
import id.homebase.resources.chat_view_once_mute
import id.homebase.resources.chat_view_once_retry
import id.homebase.resources.chat_view_once_unmute
import id.homebase.resources.chat_view_once_viewer_failed_body
import id.homebase.resources.chat_view_once_viewer_failed_title
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

const val VIEW_ONCE_VIEWER_CLOSE_TAG = "viewOnceViewerClose"
const val VIEW_ONCE_VIEWER_RETRY_TAG = "viewOnceViewerRetry"
const val VIEW_ONCE_VIEWER_IMAGE_TAG = "viewOnceViewerImage"
const val VIEW_ONCE_VIEWER_PROGRESS_TAG = "viewOnceViewerProgress"
const val VIEW_ONCE_VIEWER_BLOCKED_TAG = "viewOnceViewerBlocked"
const val VIEW_ONCE_VIEWER_MUTE_TAG = "viewOnceViewerMute"
const val VIEW_ONCE_VIEWER_CAPTION_TAG = "viewOnceViewerCaption"
const val VIEW_ONCE_VIEWER_REACT_TAG = "viewOnceViewerReact"
const val VIEW_ONCE_VIEWER_REACTION_TAG_PREFIX = "viewOnceViewerReaction_"
const val VIEW_ONCE_VIEWER_REPLY_TAG = "viewOnceViewerReply"
const val VIEW_ONCE_VIEWER_PLAY_TAG = "viewOnceViewerPlay"
const val VIEW_ONCE_VIEWER_ELAPSED_TAG = "viewOnceViewerElapsed"
const val VIEW_ONCE_VIEWER_REMAINING_TAG = "viewOnceViewerRemaining"
const val VIEW_ONCE_VIEWER_INFO_TAG = "viewOnceViewerInfo"
const val VIEW_ONCE_VIEWER_TITLE_TAG = "viewOnceViewerTitle"

/**
 * Full-screen viewer for one received view-once item. It has no save, share, forward or paging,
 * and it reads the payload only through [loader]. However it ends (back, reply, the app
 * leaving the foreground, or leaving composition) [onViewerClosed] runs exactly once, and only
 * if the media was actually shown: an item that never loaded is not used up. Reacting never ends it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ViewOnceViewer(
    data: FullScreenOverlay.ViewOnceViewer,
    onViewerClosed: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onScreenshot: () -> Unit = {},
    reactions: List<String> = quickReactions(emptyList()),
    onReact: (String) -> Unit = {},
    onReply: () -> Unit = {},
    loader: ViewOncePayloadLoader = koinInject(),
    captureObserver: @Composable (onScreenshot: () -> Unit) -> State<Boolean> = { rememberScreenCaptureObserver(it) },
) {
    SecureWindowEffect(active = true)
    val screenCaptured by captureObserver(onScreenshot)
    val isVideo = data.kind == ViewOnceDescriptor.KIND_VIDEO
    var imagePath by remember(data.messageId) { mutableStateOf<String?>(null) }
    var imageShown by remember(data.messageId) { mutableStateOf(false) }
    var videoShown by remember(data.messageId) { mutableStateOf(false) }
    var failed by remember(data.messageId) { mutableStateOf(false) }
    var videoReady by remember(data.messageId) { mutableStateOf(false) }
    var attempt by remember(data.messageId) { mutableIntStateOf(0) }
    var positionMs by remember(data.messageId) { mutableLongStateOf(0L) }
    var paused by remember(data.messageId) { mutableStateOf(false) }
    var ended by remember(data.messageId) { mutableStateOf(false) }
    var replayToken by remember(data.messageId) { mutableIntStateOf(0) }
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
                imagePath?.let(loader::deleteTempAsync)
                imagePath = null
                imagePath = loader.loadToTempFile(chatTargetDrive.alias, data.fileId, data.payload.key, data.keyHeader)
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

    val shown by rememberUpdatedState(imageShown || videoShown)
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
            imagePath?.let(loader::deleteTempAsync)
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
        senderName = data.senderName,
        sentAt = data.sentAt?.let { formatTimestamp(it) },
        caption = remember(data.caption) { data.caption?.let { markdownToPlainPreview(it, ViewOnceDescriptor.MAX_CAPTION_CODEPOINTS) }?.takeIf { it.isNotBlank() } },
        mediaShown = shown && !failed && !screenCaptured,
        failed = failed,
        positionMs = { positionMs },
        durationMs = durationMs,
        playing = !paused && !ended,
        onTogglePlay = {
            when {
                ended -> {
                    ended = false
                    paused = false
                    replayToken++
                }
                else -> paused = !paused
            }
        },
        muted = muted,
        onMutedChange = { muted = it },
        reactions = reactions,
        onReact = onReact,
        onReply = {
            onReply()
            close()
        },
        onClose = ::close,
        modifier = modifier,
    ) { fill, toggleChrome ->
        when {
            screenCaptured -> ViewOnceCaptureBlocked(fill)
            failed -> ViewOnceViewerFailed(onRetry = { attempt++ }, modifier = fill)
            isVideo && videoReady -> {
                VideoPlayerSurface(
                    data = data.videoPlayerData(),
                    modifier = Modifier.fillMaxSize(),
                    muted = muted,
                    // The viewer's one chrome layer owns every control; the player's own would replace it.
                    useNativeControls = false,
                    paused = paused,
                    replayToken = replayToken,
                    onEnded = { ended = true },
                    onFirstFrame = { videoShown = true },
                    onPositionUpdate = { positionMs = it },
                    onError = { failed = true },
                )
                if (!videoShown) ViewOnceViewerLoading(fill)
            }
            imagePath != null -> {
                ViewOnceViewerImage(
                    path = imagePath!!,
                    onTap = toggleChrome,
                    onLoaded = { imageShown = true },
                    onError = { failed = true },
                )
                if (!imageShown) ViewOnceViewerLoading(fill)
            }
            else -> ViewOnceViewerLoading(fill)
        }
    }
}

internal fun FullScreenOverlay.ViewOnceViewer.videoPlayerData() = FullScreenOverlay.VideoPlayerData(
    fileId = fileId,
    driveId = chatTargetDrive.alias,
    payloadKey = payload.key,
    keyHeader = keyHeader,
    payload = payload,
    scratchSub = AppCacheDirs.VIEW_ONCE,
)

/**
 * Always dark, like the camera: media reads best on black, and the chrome must not flip with the app theme.
 * The media fills the screen and the chrome floats over it on scrims. A tap on the media hides only the
 * top row's title and mark; back, react and reply stay, because they are the ways out and the only actions.
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
    senderName: String? = null,
    sentAt: String? = null,
    positionMs: () -> Long = { 0L },
    durationMs: Long? = null,
    playing: Boolean = true,
    onTogglePlay: (() -> Unit)? = null,
    muted: Boolean = false,
    onMutedChange: ((Boolean) -> Unit)? = null,
    reactions: List<String> = quickReactions(emptyList()),
    onReact: (String) -> Unit = {},
    onReply: () -> Unit = {},
    body: @Composable BoxScope.(fill: Modifier, toggleChrome: () -> Unit) -> Unit,
) {
    HomebaseTheme(darkTheme = true, followsSystemTheme = false, updatesSystemChrome = false) {
        val motion = MaterialTheme.motionScheme
        var topRequested by remember { mutableStateOf(true) }
        var infoOpen by remember { mutableStateOf(false) }
        LaunchedEffect(playing) { if (!playing) topRequested = true }
        val topVisible = topRequested || !mediaShown
        Box(
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim)
                .pointerInput(mediaShown) { detectTapGestures { if (mediaShown) topRequested = !topRequested } },
        ) {
            body(Modifier.fillMaxSize()) { if (mediaShown) topRequested = !topRequested }
            AnimatedVisibility(
                visible = topVisible,
                enter = fadeIn(motion.defaultEffectsSpec()),
                exit = fadeOut(motion.defaultEffectsSpec()),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                ChromeScrim(fromTop = true, modifier = Modifier.fillMaxWidth().height(TOP_SCRIM))
            }
            ViewOnceViewerTopBar(
                senderName = senderName,
                sentAt = sentAt,
                titleVisible = topVisible,
                onClose = onClose,
                onInfo = { infoOpen = true },
                modifier = Modifier.align(Alignment.TopCenter),
            )
            ViewOnceViewerBottomBar(
                isVideo = isVideo,
                caption = caption?.takeUnless { failed },
                positionMs = positionMs,
                durationMs = durationMs,
                playing = playing,
                onTogglePlay = onTogglePlay?.takeIf { mediaShown },
                muted = muted,
                onMutedChange = onMutedChange?.takeIf { mediaShown },
                reactions = reactions,
                onReact = onReact,
                onReply = onReply,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            if (infoOpen) ViewOnceIntroSheet(isVideo = isVideo, onDismiss = { infoOpen = false })
        }
    }
}

@Composable
private fun chromeContainer(): Color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = CHROME_ALPHA)

@Composable
private fun ChromeScrim(fromTop: Boolean, modifier: Modifier = Modifier) {
    val scrim = MaterialTheme.colorScheme.scrim
    val stops = if (fromTop) {
        arrayOf(0f to scrim.copy(alpha = SCRIM_ALPHA), 1f to scrim.copy(alpha = 0f))
    } else {
        arrayOf(0f to scrim.copy(alpha = 0f), 0.45f to scrim.copy(alpha = SCRIM_ALPHA * 0.75f), 1f to scrim.copy(alpha = SCRIM_ALPHA))
    }
    Box(modifier.background(Brush.verticalGradient(colorStops = stops)))
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChromeCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = chromeContainer(),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = modifier.size(CHROME_SIZE),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ViewOnceViewerTopBar(
    senderName: String?,
    sentAt: String?,
    titleVisible: Boolean,
    onClose: () -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val markLabel = stringResource(MR.string.cd_view_once_toggle)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChromeCircleButton(onClick = onClose, modifier = Modifier.testTag(VIEW_ONCE_VIEWER_CLOSE_TAG)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(MR.string.chat_view_once_close))
        }
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            if (senderName != null) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = titleVisible,
                    enter = fadeIn(motion.defaultEffectsSpec()),
                    exit = fadeOut(motion.defaultEffectsSpec()),
                ) {
                    Surface(shape = CircleShape, color = chromeContainer(), contentColor = colors.onSurface) {
                        Column(
                            Modifier
                                .heightIn(min = CHROME_SIZE)
                                .padding(horizontal = 18.dp, vertical = 6.dp)
                                .semantics(mergeDescendants = true) {}
                                .testTag(VIEW_ONCE_VIEWER_TITLE_TAG),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = senderName,
                                style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (sentAt != null) {
                                Text(
                                    text = sentAt,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = titleVisible,
            enter = fadeIn(motion.defaultEffectsSpec()),
            exit = fadeOut(motion.defaultEffectsSpec()),
        ) {
            ChromeCircleButton(onClick = onInfo, modifier = Modifier.testTag(VIEW_ONCE_VIEWER_INFO_TAG)) {
                Icon(ViewOnceIcon, contentDescription = markLabel, modifier = Modifier.size(IconButtonDefaults.mediumIconSize))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ViewOnceViewerBottomBar(
    isVideo: Boolean,
    caption: String?,
    positionMs: () -> Long,
    durationMs: Long?,
    playing: Boolean,
    onTogglePlay: (() -> Unit)?,
    muted: Boolean,
    onMutedChange: ((Boolean) -> Unit)?,
    reactions: List<String>,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    var picking by remember { mutableStateOf(false) }
    var reacted by remember { mutableStateOf<String?>(null) }
    Box(modifier.fillMaxWidth()) {
        ChromeScrim(fromTop = false, modifier = Modifier.matchParentSize())
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = BOTTOM_BAR_MAX_WIDTH)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = BOTTOM_FADE, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (caption != null) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = chromeContainer(),
                    contentColor = colors.onSurface,
                ) {
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                        modifier = Modifier
                            .heightIn(max = CAPTION_MAX_HEIGHT)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .testTag(VIEW_ONCE_VIEWER_CAPTION_TAG),
                    )
                }
            }
            if (isVideo && onTogglePlay != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChromeCircleButton(onClick = onTogglePlay, modifier = Modifier.testTag(VIEW_ONCE_VIEWER_PLAY_TAG)) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(if (playing) MR.string.chat_view_once_pause else MR.string.chat_view_once_play),
                        )
                    }
                    if (onMutedChange != null) {
                        ChromeCircleButton(onClick = { onMutedChange(!muted) }, modifier = Modifier.testTag(VIEW_ONCE_VIEWER_MUTE_TAG)) {
                            Icon(
                                if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = stringResource(if (muted) MR.string.chat_view_once_unmute else MR.string.chat_view_once_mute),
                            )
                        }
                    }
                    val duration = durationMs?.takeIf { it > 0 }
                    val position = positionMs().coerceAtLeast(0L).let { if (duration != null) it.coerceAtMost(duration) else it }
                    PlaybackTime(formatPlaybackTime(position), Modifier.padding(start = 4.dp).testTag(VIEW_ONCE_VIEWER_ELAPSED_TAG))
                    if (duration != null) {
                        val smooth by rememberPlaybackProgress(positionMs, duration)
                        LinearProgressIndicator(
                            progress = { smooth },
                            color = colors.primary,
                            trackColor = colors.onSurface.copy(alpha = 0.24f),
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                            modifier = Modifier.weight(1f).height(4.dp).testTag(VIEW_ONCE_VIEWER_PROGRESS_TAG),
                        )
                        PlaybackTime("-" + formatPlaybackTime(duration - position), Modifier.testTag(VIEW_ONCE_VIEWER_REMAINING_TAG))
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
            AnimatedVisibility(
                visible = picking,
                enter = fadeIn(motion.defaultEffectsSpec()) + expandVertically(motion.defaultSpatialSpec()),
                exit = fadeOut(motion.fastEffectsSpec()) + shrinkVertically(motion.fastSpatialSpec()),
            ) {
                Surface(shape = CircleShape, color = chromeContainer()) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        reactions.forEachIndexed { index, emoji ->
                            Box(
                                Modifier
                                    .minimumInteractiveComponentSize()
                                    .clip(CircleShape)
                                    .clickable {
                                        reacted = emoji
                                        picking = false
                                        onReact(emoji)
                                    }
                                    .semantics { contentDescription = emoji }
                                    .testTag("$VIEW_ONCE_VIEWER_REACTION_TAG_PREFIX$index"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(text = emoji.withEmojiFont(), style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val emojiLabel = stringResource(MR.string.chat_message_emoji_options)
                ChromeCircleButton(onClick = { if (reacted == null) picking = !picking }, modifier = Modifier.testTag(VIEW_ONCE_VIEWER_REACT_TAG)) {
                    val current = reacted
                    if (current == null) {
                        Icon(Icons.Outlined.EmojiEmotions, contentDescription = emojiLabel)
                    } else {
                        Text(text = current.withEmojiFont(), style = MaterialTheme.typography.titleLarge)
                    }
                }
                ChromeCircleButton(onClick = onReply, modifier = Modifier.testTag(VIEW_ONCE_VIEWER_REPLY_TAG)) {
                    Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = stringResource(MR.string.chat_message_reply))
                }
            }
        }
    }
}

// A clock reads left to right in every script, so an RTL layout must not reorder "0:05" or "-1:20".
@Composable
private fun PlaybackTime(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Ltr, fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = modifier,
    )
}

internal fun formatPlaybackTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) + 500L) / 1000L
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$ss" else "$minutes:$ss"
}

@Composable
private fun rememberPlaybackProgress(positionMs: () -> Long, durationMs: Long): State<Float> {
    // Position arrives about twice a second; easing between ticks keeps the bar moving steadily.
    val position = positionMs().coerceIn(0L, durationMs)
    return animateFloatAsState(position.toFloat() / durationMs, tween(POSITION_TICK_MS, easing = LinearEasing))
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
        modifier = modifier.verticalScroll(rememberScrollState()).padding(start = 32.dp, end = 32.dp, top = 88.dp, bottom = 120.dp),
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
internal fun ViewOnceViewerImage(
    path: String,
    onTap: () -> Unit,
    onLoaded: () -> Unit = {},
    onError: () -> Unit = {},
) {
    ZoomableSubSamplingImage(
        source = remember(path) { SubSamplingImageSource.LocalFile(path) },
        contentDescription = stringResource(MR.string.chat_message_image_attachment),
        onTap = onTap,
        onLoaded = onLoaded,
        onError = onError,
        modifier = Modifier.fillMaxSize().testTag(VIEW_ONCE_VIEWER_IMAGE_TAG),
    )
}

private const val POSITION_TICK_MS = 500
private const val CHROME_ALPHA = 0.62f
private const val SCRIM_ALPHA = 0.64f
private val CHROME_SIZE = 48.dp
private val BOTTOM_FADE = 72.dp
private val TOP_SCRIM = 160.dp
private val CAPTION_MAX_HEIGHT = 160.dp
private val BOTTOM_BAR_MAX_WIDTH = 640.dp

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
