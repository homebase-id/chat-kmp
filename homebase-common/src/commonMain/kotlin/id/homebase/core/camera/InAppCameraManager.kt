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
import id.homebase.core.settings.rememberMirrorFrontCamera
import id.homebase.core.util.PlatformCameraManager
import io.github.vinceglb.filekit.PlatformFile
import co.touchlab.kermit.Logger
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

// A receiver that never reports its content drawn can't hold the camera over the app for longer than this.
internal const val HANDOFF_CEILING_MS = 1_500L

/** True once the receiver reported the capture drawn, false when the ceiling ran out first. */
internal suspend fun awaitHandoff(epoch: Int, ceilingMs: Long = HANDOFF_CEILING_MS): Boolean =
    withTimeoutOrNull(ceilingMs) { CaptureHandoff.awaitShown(epoch) } != null

@Stable
class InAppCameraLauncher internal constructor() : PlatformCameraManager {
    internal var isOpen by mutableStateOf(false)
    internal var galleryRequested by mutableStateOf(false)
    /** Set while a delivered capture waits for its receiver to draw it, see [CaptureHandoff]. */
    internal var handoffEpoch by mutableStateOf<Int?>(null)
    internal var warmer: CameraWarmer? = null
    internal var warmEngine: CameraEngine? = null
    internal var recordsVideo = false

    override fun launch() {
        if (isOpen) return
        warmEngine = warmer?.warm(recordsVideo)
        isOpen = true
    }

    internal fun close() {
        isOpen = false
        handoffEpoch = null
    }
}

// With awaitResultShown a capture keeps the camera up until CaptureHandoff.contentShown, then it fades out.
@Composable
fun rememberInAppCameraManager(
    allowedModes: CameraModes,
    awaitResultShown: Boolean = false,
    onOpenGallery: (() -> Unit)? = null,
    onResult: (PlatformFile?) -> Unit,
): InAppCameraLauncher {
    val launcher = remember { InAppCameraLauncher() }
    launcher.warmer = rememberCameraWarmer()
    launcher.recordsVideo = allowedModes.recordsVideo
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnOpenGallery by rememberUpdatedState(onOpenGallery)
    if (launcher.isOpen) {
        val fade = remember { Animatable(1f) }
        val handoff = launcher.handoffEpoch
        if (handoff != null) {
            val exitSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            LaunchedEffect(handoff) {
                val waiting = TimeSource.Monotonic.markNow()
                if (awaitHandoff(handoff)) {
                    Logger.d(tag = "InAppCamera") { "Capture receiver drew it after ${waiting.elapsedNow().inWholeMilliseconds} ms" }
                } else {
                    Logger.w(tag = "InAppCamera") { "Capture receiver never reported it drawn; closing at the ceiling" }
                }
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
            mirrorFront = rememberMirrorFrontCamera(),
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
