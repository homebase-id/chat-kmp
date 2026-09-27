@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package id.homebase.core.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme

// A window-wide slide reads as a phone transition on a desktop display.
internal actual object PlatformNavMotion {
    actual fun push(scheme: MotionScheme): EnterTransition = fadeThroughIn(scheme)

    actual fun pushExit(scheme: MotionScheme): ExitTransition = fadeThroughOut(scheme)

    actual fun pop(scheme: MotionScheme): EnterTransition = fadeThroughIn(scheme)

    actual fun popExit(scheme: MotionScheme): ExitTransition = fadeThroughOut(scheme)
}
