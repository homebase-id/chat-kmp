package id.homebase.core.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.staticCompositionLocalOf

// What a screen needs to keep a piece of itself still across a NavHost transition (a shared element).
@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

internal val LocalNavAnimatedVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }
