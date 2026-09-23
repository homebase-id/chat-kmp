@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package id.homebase.core.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme

// Material shared axis X; navigation-compose seeks these for predictive back.
internal actual object PlatformNavMotion {
    actual fun push(scheme: MotionScheme): EnterTransition =
        slideInHorizontally(scheme.defaultSpatialSpec()) { it / 10 } + fadeIn(scheme.defaultEffectsSpec())

    actual fun pushExit(scheme: MotionScheme): ExitTransition =
        slideOutHorizontally(scheme.defaultSpatialSpec()) { -it / 10 } + fadeOut(scheme.fastEffectsSpec())

    actual fun pop(scheme: MotionScheme): EnterTransition =
        slideInHorizontally(scheme.defaultSpatialSpec()) { -it / 10 } + fadeIn(scheme.defaultEffectsSpec())

    actual fun popExit(scheme: MotionScheme): ExitTransition =
        slideOutHorizontally(scheme.defaultSpatialSpec()) { it / 10 } + fadeOut(scheme.fastEffectsSpec())
}
