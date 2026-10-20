package id.homebase.core.avatars

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

data class AvatarOptions(
    val size: Dp = 48.dp,
    val fontSize: TextUnit? = null,
    val onClick: (() -> Unit)? = null,
    val contentScale: ContentScale = ContentScale.Crop,
    /** Unspecified resolves to the secondaryContainer pair. Set both when the avatar sits on
     *  something other than a surface role, which that pair is only legible against. */
    val containerColor: Color = Color.Unspecified,
    val contentColor: Color = Color.Unspecified,
)