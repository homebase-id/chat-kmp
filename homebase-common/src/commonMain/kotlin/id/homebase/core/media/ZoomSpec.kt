package id.homebase.core.media

import androidx.compose.runtime.Immutable

@Immutable
data class ZoomSpec(
    val minScale: Float = 1f,
    val maxScale: Float = 5f,
    val overzoomEffect: OverzoomEffect = OverzoomEffect.RubberBanding,
)

enum class OverzoomEffect {
    RubberBanding,
    Disabled,
    NoLimits,
}
