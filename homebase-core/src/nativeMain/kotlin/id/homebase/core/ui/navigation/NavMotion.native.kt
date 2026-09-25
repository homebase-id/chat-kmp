@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package id.homebase.core.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme

// UIKit push: full-width slide over a parallaxed parent. Effects springs, because a spatial one
// overshoots a full-width slide past the screen edge.
internal actual object PlatformNavMotion {
    actual fun push(scheme: MotionScheme): EnterTransition =
        slideInHorizontally(scheme.slowEffectsSpec()) { it }

    actual fun pushExit(scheme: MotionScheme): ExitTransition =
        slideOutHorizontally(scheme.slowEffectsSpec()) { -it / 3 }

    actual fun pop(scheme: MotionScheme): EnterTransition =
        slideInHorizontally(scheme.slowEffectsSpec()) { -it / 3 }

    actual fun popExit(scheme: MotionScheme): ExitTransition =
        slideOutHorizontally(scheme.slowEffectsSpec()) { it }
}
