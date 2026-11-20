package id.homebase.core.image

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect

@Composable
internal actual fun HdrPhotoOverlay(
    active: Boolean,
    encoded: suspend () -> ByteArray?,
    path: String?,
    displayedRect: () -> Rect,
    modifier: Modifier,
) = Unit
