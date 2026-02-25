package id.homebase.core.avatars

import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

data class AvatarOptions(
    val size: Dp = 48.dp,
    val initialsFontSize: TextUnit? = null,
    val onClick: (() -> Unit)? = null,
    val contentScale: ContentScale = ContentScale.Crop
)