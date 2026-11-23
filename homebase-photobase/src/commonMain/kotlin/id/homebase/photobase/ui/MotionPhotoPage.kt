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

/**
 * One opening of a Live or motion photo: its clip plays once, shows from its first frame, and hands back to the still when it
 * ends, fails or the photo is touched (THUMBNAILS.md, "Live and motion photos").
 */
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

/**
 * The still, as any photo, with its clip played once over it, muted and without controls, each time the page settles, as
 * Apple's Photos and Google Photos do. The clip fills the still's frame and fades back to it.
 */
@Composable
internal fun MotionPhotoPage(
    still: FullScreenOverlay.ViewMessageData,
    clip: FullScreenOverlay.VideoPlayerData,
    stillAspectRatio: Float,
    settled: Boolean,
    showUIState: MutableState<Boolean>,
    onDismiss: () -> Unit,
) {
    val playback = remember(clip.fileId, settled) { MotionPlayback(play = settled) }
    val alpha by animateFloatAsState(if (playback.visible) 1f else 0f, tween(MOTION_FADE_MS), label = "motion")
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
        if (playback.playing || alpha > 0f) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                VideoPlayerSurface(
                    data = clip,
                    modifier = Modifier.aspectRatio(stillAspectRatio).graphicsLayer { this.alpha = alpha },
                    muted = true,
                    useNativeControls = false,
                    onFirstFrame = playback::onFirstFrame,
                    onEnded = playback::stop,
                    onError = { playback.stop() },
                )
            }
        }
    }
}

private const val MOTION_FADE_MS = 300
