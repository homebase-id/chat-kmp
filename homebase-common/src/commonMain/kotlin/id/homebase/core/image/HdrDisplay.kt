package id.homebase.core.image

import androidx.compose.runtime.Composable
import coil3.Image

/**
 * Shows [image] in HDR where it carries a gain map and the platform can draw one: on Android 14 and later the window
 * is in HDR mode while this is composed with such an image, and back as it was after. iOS, desktop and web draw
 * through Skia, which shows the image's SDR base; there this does nothing.
 */
@Composable
fun HdrWhileShown(image: Image?) {
    HdrWindowEffect(active = image != null && carriesGainmap(image))
}

/** Whether [image] holds an HDR gain map this platform can draw. */
internal expect fun carriesGainmap(image: Image): Boolean

/** The window in HDR mode while [active]; several holders at once are counted, and the last one restores it. */
@Composable
internal expect fun HdrWindowEffect(active: Boolean)
