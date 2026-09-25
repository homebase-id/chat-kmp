package id.homebase.core.widget

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
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
) {
    fun dismiss(then: () -> Unit = onDismiss.value) {
        val state = sheetState ?: return then()
        if (dismissing.value) return
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
 * removes the sheet from composition, so it vanishes instead of sliding out.
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
@OptIn(ExperimentalMaterial3Api::class)
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
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                dismissOnBackPress = dismissible,
                dismissOnClickOutside = dismissible,
                usePlatformDefaultWidth = false,
            ),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(max = maxWidth)
                    .heightIn(max = 680.dp),
            ) {
                remember { AdaptiveSheetScope(null, scope, currentOnDismiss, dismissing) }.content()
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
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            sheetGesturesEnabled = dismissible,
            contentWindowInsets = contentWindowInsets,
            properties = ModalBottomSheetProperties(
                shouldDismissOnBackPress = dismissible,
                shouldDismissOnClickOutside = dismissible,
            ),
        ) {
            sheetScope.content()
        }
    }
}
