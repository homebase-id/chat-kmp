package id.homebase.chat.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import id.homebase.chat.conversationlist.ConversationListUiDialog
import id.homebase.chat.conversationlist.RecipientModel
import id.homebase.chat.conversationlist.matching
import id.homebase.resources.MR
import id.homebase.resources.chat_send_file_to
import id.homebase.resources.you
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Which chat a handed-over file goes to: one tap picks it, as in the Forward sheet's list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendFileSheet(dialog: ConversationListUiDialog.SendFile, onPick: (RecipientModel) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val search = rememberTextFieldState()
    val youLabel = stringResource(MR.string.you)
    val shown = remember(search.text, dialog.recipients) { dialog.recipients.matching(search.text.toString(), youLabel) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            RecipientPicker(
                title = stringResource(MR.string.chat_send_file_to, dialog.name),
                search = search,
                recipientGroups = shown,
                selectedRecipients = emptyList(),
                listModifier = Modifier.weight(1f, fill = false),
                onRecipientSelected = { recipient ->
                    // Hidden before it goes: a sheet pulled from composition mid-animation leaves its scrim behind.
                    scope.launch {
                        sheetState.hide()
                        onPick(recipient)
                    }
                },
            )
        }
    }
}
