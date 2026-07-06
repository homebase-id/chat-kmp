package id.homebase.chat.widget

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
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
 * away — keeping the sheet (and the draft) if the user cancels. An empty
 * composer dismisses immediately, with no prompt.
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

    // No confirmValueChange veto — rejecting Hidden mid-fling oscillates the
    // sheet forever (#997); the unsaved guard lives in onDismissRequest instead.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val requestClose: () -> Unit = {
        if (hasUnsaved) showDiscardConfirm = true
        else scope.launch { sheetState.hide(); onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = { if (hasUnsaved) showDiscardConfirm = true else onDismiss() },
        sheetState = sheetState,
        // Zero insets: the defaults pad inside the draggable surface, so sheet
        // height tracks sheet position and the anchors oscillate on fling (#997).
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        content(sheetState, requestClose) { hasUnsaved = it }
    }

    if (showDiscardConfirm) {
        // A swipe/scrim/back dismissal already settled Hidden — bring the sheet back.
        val keepEditing: () -> Unit = {
            showDiscardConfirm = false
            scope.launch { sheetState.show() }
        }
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
