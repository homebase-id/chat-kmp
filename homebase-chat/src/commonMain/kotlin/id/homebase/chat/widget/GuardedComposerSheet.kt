package id.homebase.chat.widget

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import id.homebase.core.widget.AdaptiveSheet
import id.homebase.resources.MR
import id.homebase.resources.composer_discard_confirm
import id.homebase.resources.composer_discard_keep
import id.homebase.resources.composer_discard_message
import id.homebase.resources.composer_discard_title
import org.jetbrains.compose.resources.stringResource

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
    content: @Composable (
        requestClose: () -> Unit,
        reportUnsaved: (Boolean) -> Unit,
    ) -> Unit,
) {
    var hasUnsaved by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    val requestClose: () -> Unit = {
        if (hasUnsaved) showDiscardConfirm = true else onDismiss()
    }

    AdaptiveSheet(
        onDismiss = requestClose,
        dismissible = !hasUnsaved,
        expandFully = true,
        // The composer sizes itself off the sheet's own height; the default insets pad
        // inside the draggable surface, so height tracks position and the anchors
        // oscillate on fling.
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        content(requestClose) { hasUnsaved = it }
    }

    if (showDiscardConfirm) {
        val keepEditing: () -> Unit = { showDiscardConfirm = false }
        AlertDialog(
            onDismissRequest = keepEditing,
            title = { Text(stringResource(MR.string.composer_discard_title)) },
            text = { Text(stringResource(MR.string.composer_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    onDismiss()
                }) { Text(stringResource(MR.string.composer_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = keepEditing) {
                    Text(stringResource(MR.string.composer_discard_keep))
                }
            },
        )
    }
}
