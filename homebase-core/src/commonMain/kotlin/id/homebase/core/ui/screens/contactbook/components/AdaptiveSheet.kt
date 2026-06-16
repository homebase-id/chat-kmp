package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveSheet(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val wide = currentWindowAdaptiveInfo().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

    if (wide) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
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
        ModalBottomSheet(onDismissRequest = onDismiss) {
            content()
        }
    }
}
