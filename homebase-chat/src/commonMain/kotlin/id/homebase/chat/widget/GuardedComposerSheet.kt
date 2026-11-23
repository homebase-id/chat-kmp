package id.homebase.chat.widget

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import id.homebase.core.widget.AdaptiveSheet
import id.homebase.core.widget.AdaptiveSheetScope
import id.homebase.core.widget.DiscardChangesDialog

/**
 * A sheet for a content composer (Groodle / Event / Poll) that guards against accidental
 * data loss: while there's unsaved content the sheet is pinned open, so a stray swipe,
 * scrim tap or back press cannot take the draft with it. The composer's own close button
 * routes through `requestClose`, which asks "Discard changes?" instead of throwing the
 * draft away. An empty composer dismisses immediately, with no prompt.
 *
 * [content] reports whether it currently holds unsaved content through the
 * `reportUnsaved` callback, and routes its own close button through
 * `requestClose`.
 */
@Composable
internal fun GuardedComposerSheet(
    onDismiss: () -> Unit,
    content: @Composable AdaptiveSheetScope.(
        requestClose: () -> Unit,
        reportUnsaved: (Boolean) -> Unit,
    ) -> Unit,
) {
    var hasUnsaved by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    AdaptiveSheet(
        onDismiss = onDismiss,
        dismissible = !hasUnsaved,
        expandFully = true,
        // The composer sizes itself off the sheet's own height; the default insets pad
        // inside the draggable surface, so height tracks position and the anchors
        // oscillate on fling.
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        content({ if (hasUnsaved) showDiscardConfirm = true else dismiss() }) { hasUnsaved = it }

        if (showDiscardConfirm) {
            DiscardChangesDialog(
                onKeepEditing = { showDiscardConfirm = false },
                onDiscard = {
                    showDiscardConfirm = false
                    dismiss()
                },
            )
        }
    }
}
