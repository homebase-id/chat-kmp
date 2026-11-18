package id.homebase.core.image

import androidx.compose.runtime.Composable
import coil3.Image

internal actual fun carriesGainmap(image: Image): Boolean = false

@Composable
internal actual fun HdrWindowEffect(active: Boolean) = Unit
