package id.homebase.chat.widget

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import id.homebase.api.video.VideoPlayerData
import id.homebase.api.video.VideoPreloadService
import id.homebase.api.video.VideoPreloader
import kotlinx.coroutines.flow.first
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.widget.video.VideoPlayerSurface
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/** One opening of a Live or motion photo (THUMBNAILS.md, "Live and motion photos"). */
internal class MotionPlayback(play: Boolean) {
    var playing by mutableStateOf(play)
        private set
    var visible by mutableStateOf(false)
        private set

    fun onFirstFrame() {
        if (playing) visible = true
    }

    fun stop() {
        playing = false
        visible = false
    }
}

/** A photo page's clip: played once under the photo each time the page settles, faded in and out over it. */
internal class MotionState(
    val clip: FullScreenOverlay.VideoPlayerData,
    val playback: MotionPlayback,
    private val alpha: State<Float>,
    private val cached: State<Boolean>,
) {
    val shown by derivedStateOf { playback.playing || alpha.value > 0f }
    // Played from the payload cache once the preload is done, so an opening doesn't download it twice.
    val mounted by derivedStateOf { shown && cached.value }
    val clipAlpha: Float get() = alpha.value
}

@Composable
internal fun rememberMotionState(clip: FullScreenOverlay.VideoPlayerData?, settled: Boolean): MotionState? {
    if (clip == null) return null
    PreloadsVideo(clip)
    val preloader = koinInject<VideoPreloader>()
    val cached = produceState(false, clip) {
        preloader.progressFlow(clip.fileId, clip.payloadKey).first { it >= 1f }
        value = true
    }
    val playback = remember(clip, settled) { MotionPlayback(play = settled) }
    val alpha = animateFloatAsState(if (playback.visible) 1f else 0f, tween(MOTION_FADE_MS), label = "motion")
    return remember(playback) { MotionState(clip, playback, alpha, cached) }
}

/** Seen before the photo's own gestures and not consumed, so the touch that stops the clip zooms or swipes the photo too. */
internal fun Modifier.stopsMotionOnTouch(motion: MotionState?): Modifier =
    if (motion == null) this else pointerInput(motion.playback) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            motion.playback.stop()
        }
    }

/** The clip, muted and without controls, in the rect the photo is displayed in. */
@Composable
internal fun MotionClipUnderPhoto(motion: MotionState, displayedRect: () -> Rect) {
    // A new player per opening: onFirstFrame fires once per mount.
    key(motion.playback) {
        VideoPlayerSurface(
            data = motion.clip,
            modifier = Modifier.layout { measurable, _ ->
                val rect = displayedRect()
                val width = rect.width.roundToInt().coerceAtLeast(0)
                val height = rect.height.roundToInt().coerceAtLeast(0)
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, height) { placeable.place(rect.left.roundToInt(), rect.top.roundToInt()) }
            },
            muted = true,
            useNativeControls = false,
            onFirstFrame = motion.playback::onFirstFrame,
            onEnded = motion.playback::stop,
            onError = { motion.playback.stop() },
        )
    }
}

/** Puts the video into the payload cache ahead of playback, as chat's bubbles do (whole MP4s up to the render limit, HLS's first segment). */
@Composable
fun PreloadsVideo(data: FullScreenOverlay.VideoPlayerData) {
    val preloader = koinInject<VideoPreloadService>()
    LaunchedEffect(data) { preloader.requestPreload(data.toVideoData()) }
}

internal fun FullScreenOverlay.VideoPlayerData.toVideoData() = VideoPlayerData(
    fileId = fileId,
    driveId = driveId,
    payloadKey = payloadKey,
    keyHeader = keyHeader,
    descriptorContent = payload.descriptorContent,
    remoteOdinId = remoteOdinId,
    globalTransitId = globalTransitId,
    scratchSub = scratchSub,
    contentType = payload.contentType,
)

private const val MOTION_FADE_MS = 300
