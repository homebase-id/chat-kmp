package id.homebase.core.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

// What a screen needs to keep a piece of itself still across a NavHost transition (a shared element).
internal val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

internal val LocalNavAnimatedVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** A NavHost goes in here so its destinations can share elements: see LocalSharedTransitionScope. */
@Composable
internal fun SharedTransitionHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    SharedTransitionLayout(modifier = modifier) {
        CompositionLocalProvider(LocalSharedTransitionScope provides this, content = content)
    }
}

/** Keeps this element still across a NavHost transition against the same [key] on the other route. */
@Composable
internal fun Modifier.navSharedElement(key: Any): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(shared) { this@navSharedElement.sharedElement(rememberSharedContentState(key), visibility) }
}
