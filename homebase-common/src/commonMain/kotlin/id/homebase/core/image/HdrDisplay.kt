package id.homebase.core.image

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import coil3.Image

/**
 * Whether [image] holds an HDR gain map this platform draws in place: Android 14 and later. iOS draws its HDR through
 * [HdrPhotoOverlay] instead; desktop and web draw through Skia, which shows the image's SDR base.
 */
internal expect fun carriesGainmap(image: Image): Boolean

/** The window in HDR mode while [active]; several holders at once are counted, and the last one restores it. */
@Composable
internal expect fun HdrWindowEffect(active: Boolean)

/**
 * While [active], the full-screen photo shows in HDR on an HDR screen when it has a gain map, where the platform
 * can't draw it in place as Android does with [HdrWindowEffect]. The photo is the local file at [path], or else
 * [encoded]; [displayedRect] is where it is shown, zoom and pan included. A no-op where HDR is drawn in place or
 * not at all.
 */
@Composable
internal expect fun HdrPhotoOverlay(
    active: Boolean,
    encoded: suspend () -> ByteArray?,
    path: String?,
    displayedRect: () -> Rect,
    modifier: Modifier = Modifier,
)
