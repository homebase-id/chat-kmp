package id.homebase.chat.widget

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally

internal const val SIGNAL_TRANSITION_MS = 150

internal val signalFadeIn: EnterTransition =
    fadeIn(tween(SIGNAL_TRANSITION_MS, easing = FastOutSlowInEasing))

internal val signalFadeOut: ExitTransition =
    fadeOut(tween(SIGNAL_TRANSITION_MS, easing = FastOutSlowInEasing))

internal val signalToggleIn: EnterTransition = scaleIn(
    initialScale = 0.6f,
    animationSpec = tween(SIGNAL_TRANSITION_MS, easing = FastOutSlowInEasing),
) + fadeIn(tween(SIGNAL_TRANSITION_MS, easing = FastOutSlowInEasing))

internal val signalToggleOut: ExitTransition = scaleOut(
    targetScale = 0.6f,
    animationSpec = tween(SIGNAL_TRANSITION_MS, easing = FastOutSlowInEasing),
) + fadeOut(tween(SIGNAL_TRANSITION_MS, easing = FastOutSlowInEasing))

internal val signalExpandHorizontally: EnterTransition =
    expandHorizontally(tween(SIGNAL_TRANSITION_MS, easing = FastOutSlowInEasing))

internal val signalShrinkHorizontally: ExitTransition =
    shrinkHorizontally(tween(SIGNAL_TRANSITION_MS, easing = FastOutSlowInEasing))
