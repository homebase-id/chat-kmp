package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.window.core.layout.WindowSizeClass

/**
 * Presents transient content adaptively: a bottom sheet on compact (phone) widths,
 * a centered constrained dialog on medium+ widths (tablet / desktop), where a
 * full-width bottom sheet looks wrong. Callers supply the same inner content for
 * both — typically a scrollable Column with its own padding.
 *
 * @param dismissible false pins the sheet open while the caller has work in flight. Gating
 *   [onDismiss] would not: `ModalBottomSheet` runs `hide()` *before* consulting `onDismissRequest`,
 *   so a refused dismissal leaves an invisible sheet mounted with no way to bring it back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveSheet(
    onDismiss: () -> Unit,
    dismissible: Boolean = true,
    content: @Composable () -> Unit,
) {
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
                    .widthIn(max = 520.dp)
                    .heightIn(max = 680.dp),
            ) {
                content()
            }
        }
    } else {
        // The drag handle's *tap* survives sheetGesturesEnabled = false; confirmValueChange is the
        // only thing that stops it. Kept identity-stable — the sheet state is keyed on this lambda.
        val canDismiss = rememberUpdatedState(dismissible)
        val sheetState = rememberModalBottomSheetState(
            confirmValueChange = remember { { it != SheetValue.Hidden || canDismiss.value } },
        )
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            sheetGesturesEnabled = dismissible,
            properties = ModalBottomSheetProperties(
                shouldDismissOnBackPress = dismissible,
                shouldDismissOnClickOutside = dismissible,
            ),
        ) {
            content()
        }
    }
}
