package id.homebase.photobase.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.widget.FullScreenMediaViewer
import id.homebase.chat.widget.video.VideoPlayerSurface

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

@Composable
internal fun PhotoPage(
    still: FullScreenOverlay.ViewMessageData,
    motion: MotionClip?,
    settled: Boolean,
    showUIState: MutableState<Boolean>,
    onDismiss: () -> Unit,
) {
    val playback = remember(settled) { MotionPlayback(play = settled && motion != null) }
    val alpha = animateFloatAsState(if (playback.visible) 1f else 0f, tween(MOTION_FADE_MS), label = "motion")
    val shown by remember(playback) { derivedStateOf { playback.playing || alpha.value > 0f } }
    Box(
        Modifier
            .fillMaxSize()
            // Seen before the still's own gestures and not consumed, so a zoom or swipe works on the still at once.
            .pointerInput(playback) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    playback.stop()
                }
            },
    ) {
        FullScreenMediaViewer(
            data = still,
            onDismiss = onDismiss,
            sharedTransitionScope = null,
            animatedVisibilityScope = null,
            showUIState = showUIState,
            isFocused = settled,
            showsHdr = !playback.playing,
        )
        if (motion != null && shown) {
            VideoPlayerSurface(
                data = motion.player,
                modifier = Modifier.align(Alignment.Center).aspectRatio(motion.aspectRatio).graphicsLayer { this.alpha = alpha.value },
                muted = true,
                useNativeControls = false,
                onFirstFrame = playback::onFirstFrame,
                onEnded = playback::stop,
                onError = { playback.stop() },
            )
        }
    }
}

private const val MOTION_FADE_MS = 300
