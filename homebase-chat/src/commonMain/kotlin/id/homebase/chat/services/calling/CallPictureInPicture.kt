package id.homebase.chat.services.calling

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow

/** Pixel size of the latest remote video frame, reported by the platform renderer; null until a frame arrives. */
object RemoteVideoSize {
    val current = MutableStateFlow<Pair<Int, Int>?>(null)
}

private const val MAX_PIP_RATIO = 2.39

/** Width:height for the system PiP window; the platforms reject ratios outside 1:2.39..2.39:1 and 9:16 is the portrait-call default. */
fun pipAspectRatio(size: Pair<Int, Int>?): Pair<Int, Int> {
    val (w, h) = size ?: return 9 to 16
    if (w <= 0 || h <= 0) return 9 to 16
    val ratio = w.toDouble() / h
    return when {
        ratio > MAX_PIP_RATIO -> 239 to 100
        ratio < 1 / MAX_PIP_RATIO -> 100 to 239
        else -> w to h
    }
}

/** True while the OS is showing this app in its picture-in-picture window. Always false where there is none. */
@Composable
expect fun rememberInPictureInPicture(): Boolean

/**
 * While [eligible], leaving the app keeps the call in a system PiP window sized from [RemoteVideoSize] with mute
 * and end-call controls. Does nothing where the platform has no PiP.
 */
@Composable
expect fun CallPictureInPictureEffect(eligible: Boolean, remote: VideoHandle?, muted: Boolean, onToggleMute: () -> Unit, onHangup: () -> Unit)

/** The PiP window only makes sense for an eligible call; once it is not, the activity must go back to full screen. */
fun shouldLeavePictureInPicture(inPip: Boolean, eligible: Boolean): Boolean = inPip && !eligible
