package id.homebase.core.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/** The newest photo or video in the device library, or null unless read access was already granted; never asks. */
@Composable
internal expect fun rememberLatestGalleryThumbnail(sizePx: Int): ImageBitmap?
