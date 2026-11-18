package id.homebase.core.image

import androidx.compose.runtime.Composable
import coil3.Image

/**
 * Whether [image] holds an HDR gain map this platform can draw: Android 14 and later. iOS, desktop and web draw
 * through Skia, which shows the image's SDR base.
 */
internal expect fun carriesGainmap(image: Image): Boolean

/** The window in HDR mode while [active]; several holders at once are counted, and the last one restores it. */
@Composable
internal expect fun HdrWindowEffect(active: Boolean)
