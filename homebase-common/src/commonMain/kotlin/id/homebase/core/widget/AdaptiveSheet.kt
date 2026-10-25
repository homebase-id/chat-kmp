package id.homebase.core.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Stable
class AdaptiveSheetScope internal constructor(
    private val sheetState: SheetState?,
    private val scope: CoroutineScope,
    private val onDismiss: State<() -> Unit>,
    private val dismissing: MutableState<Boolean>,
    private val beginExit: (((() -> Unit)) -> Unit)? = null,
) {
    fun dismiss(then: () -> Unit = onDismiss.value) {
        if (dismissing.value) return
        if (sheetState == null) {
            val begin = beginExit ?: return then()
            dismissing.value = true
            return begin(then)
        }
        val state = sheetState
        dismissing.value = true
        scope.launch { state.hide() }.invokeOnCompletion {
            if (state.isVisible) dismissing.value = false else then()
        }
    }
}

/**
 * Presents transient content adaptively: a bottom sheet on compact (phone) widths,
 * a centered constrained dialog on medium+ widths (tablet / desktop), where a
 * full-width bottom sheet looks wrong. Callers supply the same inner content for
 * both — typically a scrollable Column with its own padding.
 *
 * Close from [content] with [AdaptiveSheetScope.dismiss]: flipping the caller's flag directly
 * removes the sheet from composition, so it vanishes instead of animating out. On the wide branch
 * dismiss(), Esc, back and click-outside all run the exit before calling [onDismiss].
 *
 * @param expandFully opens at full height instead of half — for content whose primary action
 *   sits below a form the user would otherwise have to scroll to reach.
 * @param maxWidth caps the wide (dialog) branch only; the compact sheet is always full width.
 * @param dismissible false pins the sheet open while the caller has work in flight. Gating
 *   [onDismiss] would not: `ModalBottomSheet` runs `hide()` *before* consulting `onDismissRequest`,
 *   so a refused dismissal leaves an invisible sheet mounted with no way to bring it back.
 * @param contentWindowInsets compact branch only. Content that sizes itself off the sheet's own
 *   height (`fillMaxHeight(fraction)`) must pass zero here — the default insets pad inside the
 *   draggable surface, so height tracks position and the anchors oscillate on fling.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AdaptiveSheet(
    onDismiss: () -> Unit,
    dismissible: Boolean = true,
    expandFully: Boolean = false,
    maxWidth: Dp = 520.dp,
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    content: @Composable AdaptiveSheetScope.() -> Unit,
) {
    val currentOnDismiss = rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    val dismissing = remember { mutableStateOf(false) }
    val wide = currentWindowAdaptiveInfo().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

    if (wide) {
        val transition = remember { MutableTransitionState(false).apply { targetState = true } }
        val pendingThen = remember { mutableStateOf<(() -> Unit)?>(null) }
        val wideScope = remember {
            AdaptiveSheetScope(null, scope, currentOnDismiss, dismissing) { then ->
                pendingThen.value = then
                transition.targetState = false
            }
        }
        LaunchedEffect(pendingThen.value) {
            val then = pendingThen.value ?: return@LaunchedEffect
            snapshotFlow { transition.isIdle && !transition.currentState }.first { it }
            then()
        }
        Dialog(
            onDismissRequest = { wideScope.dismiss() },
            properties = DialogProperties(
                dismissOnBackPress = dismissible,
                dismissOnClickOutside = dismissible,
                usePlatformDefaultWidth = false,
            ),
        ) {
            val motion = MaterialTheme.motionScheme
            AnimatedVisibility(
                visibleState = transition,
                enter = scaleIn(motion.defaultSpatialSpec(), initialScale = 0.9f) +
                    fadeIn(motion.defaultEffectsSpec()),
                exit = scaleOut(motion.defaultSpatialSpec(), targetScale = 0.9f) +
                    fadeOut(motion.defaultEffectsSpec()),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .padding(24.dp)
                        .widthIn(max = maxWidth)
                        .heightIn(max = 680.dp),
                ) {
                    wideScope.content()
                }
            }
        }
    } else {
        // The drag handle's *tap* survives sheetGesturesEnabled = false; confirmValueChange is the
        // only thing that stops it, and it gates our own hide() too. Kept identity-stable — the
        // sheet state is keyed on this lambda.
        val canDismiss = rememberUpdatedState(dismissible)
        val sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = expandFully,
            confirmValueChange = remember {
                { it != SheetValue.Hidden || canDismiss.value || dismissing.value }
            },
        )
        val sheetScope = remember(sheetState) {
            AdaptiveSheetScope(sheetState, scope, currentOnDismiss, dismissing)
        }
        val screenIme = WindowInsets.ime
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            sheetGesturesEnabled = dismissible,
            contentWindowInsets = {
                KeyboardLiftedSheetInsets(contentWindowInsets(), screenIme, WindowInsets.ime, WindowInsets.navigationBars)
            },
            properties = ModalBottomSheetProperties(
                shouldDismissOnBackPress = dismissible,
                shouldDismissOnClickOutside = dismissible,
            ),
        ) {
            sheetScope.content()
        }
    }
}

/**
 * On iOS the sheet's layer is lifted onto the keyboard, so inside it the IME reads zero while the home
 * indicator still reads its full inset, padding a strip of sheet above the keys. Read at layout time.
 */
internal class KeyboardLiftedSheetInsets(
    private val base: WindowInsets,
    private val screenIme: WindowInsets,
    private val sheetIme: WindowInsets,
    private val navigationBars: WindowInsets,
) : WindowInsets by base {
    override fun getBottom(density: Density): Int {
        val bottom = base.getBottom(density)
        val lifted = screenIme.getBottom(density) > 0 && sheetIme.getBottom(density) == 0
        return if (lifted) (bottom - navigationBars.getBottom(density)).coerceAtLeast(0) else bottom
    }
}
