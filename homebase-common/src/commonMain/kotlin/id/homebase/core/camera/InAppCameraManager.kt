package id.homebase.core.camera

import androidx.compose.animation.core.Animatable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import id.homebase.core.util.PlatformCameraManager
import io.github.vinceglb.filekit.PlatformFile
import co.touchlab.kermit.Logger
import kotlinx.coroutines.withTimeoutOrNull

// A receiver that never reports its content drawn can't hold the camera over the app for longer than this.
internal const val HANDOFF_CEILING_MS = 1_500L

/** True once the receiver reported the capture drawn, false when the ceiling ran out first. */
internal suspend fun awaitHandoff(epoch: Int, ceilingMs: Long = HANDOFF_CEILING_MS): Boolean =
    withTimeoutOrNull(ceilingMs) { CaptureHandoff.awaitShown(epoch) } != null

@Stable
class InAppCameraLauncher internal constructor() : PlatformCameraManager {
    internal var openMode by mutableStateOf<CaptureMode?>(null)
    internal var galleryRequested by mutableStateOf(false)
    /** Set while a delivered capture waits for its receiver to draw it, see [CaptureHandoff]. */
    internal var handoffEpoch by mutableStateOf<Int?>(null)
    internal var warmer: CameraWarmer? = null
    internal var warmEngine: CameraEngine? = null
    internal var recordsVideo = false

    override fun launch() = launch(CaptureMode.Photo)

    fun launch(initialMode: CaptureMode) {
        if (openMode != null) return
        warmEngine = warmer?.warm(recordsVideo)
        openMode = initialMode
    }

    internal fun close() {
        openMode = null
        handoffEpoch = null
    }
}

/**
 * Emits the camera dialog while open, so call it unconditionally (not inside an `if`).
 * [onResult] gets the captured file, or null when the camera is closed without one. [onOpenGallery], when given,
 * shows a gallery button that closes the camera and hands over to the caller's picker. With [awaitResultShown] the
 * camera stays up after a capture until the receiver calls [CaptureHandoff.contentShown], then fades out.
 */
@Composable
fun rememberInAppCameraManager(
    allowedModes: CameraModes,
    mirrorFront: Boolean = true,
    awaitResultShown: Boolean = false,
    onOpenGallery: (() -> Unit)? = null,
    onResult: (PlatformFile?) -> Unit,
): InAppCameraLauncher {
    val launcher = remember { InAppCameraLauncher() }
    launcher.warmer = rememberCameraWarmer()
    launcher.recordsVideo = allowedModes.allows(CaptureMode.Video)
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnOpenGallery by rememberUpdatedState(onOpenGallery)
    val mode = launcher.openMode
    if (mode != null) {
        val fade = remember { Animatable(1f) }
        val handoff = launcher.handoffEpoch
        if (handoff != null) {
            val exitSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            LaunchedEffect(handoff) {
                if (!awaitHandoff(handoff)) Logger.w(tag = "InAppCamera") { "Capture receiver never reported it drawn; closing at the ceiling" }
                fade.animateTo(0f, exitSpec)
                launcher.close()
            }
        }
        DisposableEffect(launcher) {
            onDispose {
                // Adopted engines are released by the camera screen; this catches one it never got to.
                launcher.warmEngine?.release()
                launcher.warmEngine = null
            }
        }
        CameraCaptureDialog(
            allowedModes = allowedModes,
            initialMode = mode,
            mirrorFront = mirrorFront,
            warmEngine = launcher.warmEngine,
            handingOff = handoff != null,
            fade = { fade.value },
            onOpenGallery = if (onOpenGallery == null) null else {
                {
                    launcher.close()
                    launcher.galleryRequested = true
                }
            },
            onResult = { file ->
                if (awaitResultShown) launcher.handoffEpoch = CaptureHandoff.begin() else launcher.close()
                currentOnResult(file)
            },
            onDismiss = {
                val delivered = launcher.handoffEpoch != null
                launcher.close()
                if (!delivered) currentOnResult(null)
            },
        )
    } else if (launcher.galleryRequested) {
        // iOS presents the picker on the key window, which stays the camera dialog's until it leaves composition.
        LaunchedEffect(Unit) {
            launcher.galleryRequested = false
            currentOnOpenGallery?.invoke()
        }
    }
    return launcher
}
