package id.homebase.core.ui.screens.card

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal val CAPTURE_HOST_WIDTH = 240.dp
internal val CAPTURE_LAYOUT_WIDTH = 360.dp

internal fun audienceKey(audience: CardAudience): String = when (audience) {
    CardAudience.Public -> "public"
    is CardAudience.Circle -> audience.id.lowercase()
}

@Composable
internal fun CardStill(card: ProfileCard, still: ImageBitmap?, modifier: Modifier) {
    if (still != null) {
        // Stills are laid out at the tile's shape; a taller one (a page export at 390x844) shows its top, like the live view.
        Image(
            bitmap = still,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = modifier,
        )
    } else {
        val palette = card.overrides.palette
        CardDesignThumbnail(
            design = card.design,
            groundOverride = palette?.ground?.let { Color(hexToArgb(it)) },
            inkOverride = palette?.ink?.let { Color(hexToArgb(it)) },
            accentOverride = palette?.accent?.let { Color(hexToArgb(it)) },
            modifier = modifier,
        )
    }
}
