package id.homebase.chat.widget

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import id.homebase.resources.MR
import id.homebase.resources.composer_discard_confirm
import id.homebase.resources.composer_discard_keep
import id.homebase.resources.composer_discard_message
import id.homebase.resources.composer_discard_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * A [ModalBottomSheet] for a content composer (Groodle / Event / Poll) that
 * guards against accidental data loss (#891): when the user swipes the sheet
 * down, taps the scrim, presses back, or taps the close button **while there's
 * unsaved content**, it asks "Discard changes?" instead of throwing the draft
 * away. An empty composer dismisses immediately, with no prompt.
 *
 * [content] reports whether it currently holds unsaved content through the
 * `reportUnsaved` callback, and routes its own close button through
 * `requestClose`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GuardedComposerSheet(
    onDismiss: () -> Unit,
    content: @Composable (
        sheetState: SheetState,
        requestClose: () -> Unit,
        reportUnsaved: (Boolean) -> Unit,
    ) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var hasUnsaved by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    // confirmValueChange rejects a swipe/scrim drag toward Hidden mid-gesture —
    // the sheet snaps back (no flash to a half-dismissed state) and we pop the
    // confirm dialog. onDismissRequest covers the predictive-back path, which
    // does not always route through confirmValueChange.
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            if (target == SheetValue.Hidden && hasUnsaved) {
                showDiscardConfirm = true
                false
            } else {
                true
            }
        },
    )

    val requestClose: () -> Unit = {
        if (hasUnsaved) showDiscardConfirm = true
        else scope.launch { sheetState.hide(); onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = { if (hasUnsaved) showDiscardConfirm = true else onDismiss() },
        sheetState = sheetState,
    ) {
        content(sheetState, requestClose) { hasUnsaved = it }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(MR.string.composer_discard_title)) },
            text = { Text(stringResource(MR.string.composer_discard_message)) },
            confirmButton = {
                // Discard: drop the sheet outright (the host removes it from
                // composition) — no animated hide() to fight confirmValueChange.
                TextButton(onClick = {
                    showDiscardConfirm = false
                    onDismiss()
                }) { Text(stringResource(MR.string.composer_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text(stringResource(MR.string.composer_discard_keep))
                }
            },
        )
    }
}
