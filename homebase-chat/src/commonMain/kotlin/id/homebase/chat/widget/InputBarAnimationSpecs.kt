package id.homebase.chat.widget

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
internal fun signalFadeIn(): EnterTransition = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec())

@Composable
internal fun signalFadeOut(): ExitTransition = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())

@Composable
internal fun signalToggleIn(): EnterTransition = scaleIn(
    initialScale = 0.6f,
    animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
) + fadeIn(MaterialTheme.motionScheme.fastEffectsSpec())

@Composable
internal fun signalToggleOut(): ExitTransition = scaleOut(
    targetScale = 0.6f,
    animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())

@Composable
internal fun signalExpandHorizontally(): EnterTransition =
    expandHorizontally(MaterialTheme.motionScheme.fastSpatialSpec())

@Composable
internal fun signalShrinkHorizontally(): ExitTransition =
    shrinkHorizontally(MaterialTheme.motionScheme.fastSpatialSpec())
