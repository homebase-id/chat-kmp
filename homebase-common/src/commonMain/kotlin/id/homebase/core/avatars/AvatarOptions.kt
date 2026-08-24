package id.homebase.core.avatars

import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

data class AvatarOptions(
    val size: Dp = 48.dp,
    val fontSize: TextUnit? = null,
    val onClick: (() -> Unit)? = null,
    /** [onClick] opens the photo itself, so it fires only once the image loaded — an initials
     *  fallback stays inert. Leave false for taps that navigate; those must always work. */
    val onClickNeedsImage: Boolean = false,
    val contentScale: ContentScale = ContentScale.Crop
)