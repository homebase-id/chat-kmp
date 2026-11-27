package id.homebase.chat.widget

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.video.VideoPreloader
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.widget.video.VideoPlayerSurface
import id.homebase.chat.widget.video.playsMotionClips
import id.homebase.chat.widget.video.toVideoData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** One opening of a Live or motion photo (THUMBNAILS.md, "Live and motion photos"). */
internal class MotionState(val clip: FullScreenOverlay.VideoPlayerData, play: Boolean) {
    var playing by mutableStateOf(play)
        private set
    var visible by mutableStateOf(false)
        private set
    var preloaded by mutableStateOf(false)
    val alpha = Animatable(0f)
    val shown by derivedStateOf { playing || alpha.value > 0f }
    val mounted get() = shown && preloaded

    fun onFirstFrame() {
        if (playing) visible = true
    }

    fun stop() {
        playing = false
        visible = false
    }
}

internal fun FullScreenOverlay.ViewMessageData.motionPlayer(clip: PayloadDescriptor) = FullScreenOverlay.VideoPlayerData(
    fileId = fileId,
    driveId = driveId,
    payloadKey = clip.key,
    keyHeader = clip.iv?.let { KeyHeader(iv = it.bytes, aesKey = keyHeader.aesKey) } ?: keyHeader,
    payload = clip,
    isEncrypted = isEncrypted,
    remoteOdinId = remoteOdinId,
    globalTransitId = globalTransitId,
)

@Composable
internal fun rememberMotionState(viewer: FullScreenOverlay.ViewMessageData, clip: PayloadDescriptor?, settled: Boolean): MotionState? {
    if (clip == null || !playsMotionClips) return null
    val motion = remember(viewer, clip, settled) { MotionState(viewer.motionPlayer(clip), play = settled) }
    if (settled) {
        val preloader = koinInject<VideoPreloader>()
        // Played from the payload cache once this is done, so an opening downloads it once; swiping away cancels it.
        LaunchedEffect(motion) {
            withContext(Dispatchers.Default) { preloader.preload(motion.clip.toVideoData()) }
            motion.preloaded = true
        }
    }
    LaunchedEffect(motion) {
        snapshotFlow { motion.visible }.collectLatest { motion.alpha.animateTo(if (it) 1f else 0f, tween(MOTION_FADE_MS)) }
    }
    return motion
}

/** Seen before the photo's own gestures and not consumed, so the touch that stops the clip zooms or swipes the photo too. */
internal fun Modifier.stopsMotionOnTouch(motion: MotionState?): Modifier =
    if (motion == null) this else pointerInput(motion) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            motion.stop()
        }
    }

@Composable
internal fun MotionClipUnderPhoto(motion: MotionState, modifier: Modifier) {
    // A new player per opening: onFirstFrame fires once per mount.
    key(motion) {
        VideoPlayerSurface(
            data = motion.clip,
            modifier = modifier,
            muted = true,
            useNativeControls = false,
            // iOS: report the first frame only once the clip is ready, and keep a muted clip's audio decoder off.
            useInlineOptimizations = true,
            useZoomFill = true,
            onFirstFrame = motion::onFirstFrame,
            onEnded = motion::stop,
            onError = { motion.stop() },
        )
    }
}

private const val MOTION_FADE_MS = 300
