package id.homebase.core.widget

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection

@Stable
class TabSlideFade internal constructor(
    private val enterFade: FiniteAnimationSpec<Float>,
    private val exitFade: FiniteAnimationSpec<Float>,
    private val slide: FiniteAnimationSpec<IntOffset>,
    private val rtl: Boolean,
) {
    fun transform(forward: Boolean): ContentTransform {
        val sign = (if (forward) 1 else -1) * (if (rtl) -1 else 1)
        return (slideInHorizontally(slide) { sign * it / 10 } + fadeIn(enterFade)) togetherWith
            (slideOutHorizontally(slide) { -sign * it / 10 } + fadeOut(exitFade))
    }
}

@Composable
fun rememberTabSlideFade(): TabSlideFade {
    val scheme = MaterialTheme.motionScheme
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return remember(scheme, rtl) {
        TabSlideFade(scheme.defaultEffectsSpec(), scheme.fastEffectsSpec(), scheme.defaultSpatialSpec(), rtl)
    }
}
