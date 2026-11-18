package id.homebase.core.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity

@Stable
internal class SnackbarClearance {
    val inputTops = mutableStateMapOf<Any, Float>()
}

internal val LocalSnackbarClearance = staticCompositionLocalOf<SnackbarClearance?> { null }

/**
 * [content] with one snackbar host for [hostState], kept above any bottom input inside it that's marked
 * [keepSnackbarsAbove] (a message composer, a caption bar), so a snackbar never covers what the person types in. With
 * no such input on screen it sits at the bottom, above the navigation bar.
 */
@Composable
fun SnackbarAboveInput(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val clearance = remember { SnackbarClearance() }
    var bottom by remember { mutableFloatStateOf(0f) }
    CompositionLocalProvider(LocalSnackbarClearance provides clearance) {
        Box(modifier.fillMaxSize().onGloballyPositioned { bottom = it.boundsInRoot().bottom }) {
            content()
            val inputTop = clearance.inputTops.values.minOrNull()
            val hostModifier = if (inputTop == null) {
                Modifier.navigationBarsPadding()
            } else {
                Modifier.padding(bottom = with(LocalDensity.current) { (bottom - inputTop).coerceAtLeast(0f).toDp() })
            }
            SnackbarHost(hostState, Modifier.align(Alignment.BottomCenter).then(hostModifier))
        }
    }
}

/** While on screen inside [SnackbarAboveInput], its snackbars stay above this. */
fun Modifier.keepSnackbarsAbove(): Modifier = composed {
    val clearance = LocalSnackbarClearance.current ?: return@composed Modifier
    val key = remember { Any() }
    DisposableEffect(clearance) { onDispose { clearance.inputTops.remove(key) } }
    Modifier.onGloballyPositioned { clearance.inputTops[key] = it.positionInRoot().y }
}
