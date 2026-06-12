package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.screens.contactbook.ContactBookUiAction
import id.homebase.core.ui.screens.contactbook.ContactDraft
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.core.ui.screens.contactbook.toDraft
import id.homebase.resources.MR
import id.homebase.resources.contactbook_edit_birthday
import id.homebase.resources.contactbook_edit_cancel
import id.homebase.resources.contactbook_edit_city
import id.homebase.resources.contactbook_edit_country
import id.homebase.resources.contactbook_edit_email
import id.homebase.resources.contactbook_edit_given_name
import id.homebase.resources.contactbook_edit_phone
import id.homebase.resources.contactbook_edit_save
import id.homebase.resources.contactbook_edit_surname
import id.homebase.resources.contactbook_edit_title_edit
import id.homebase.resources.contactbook_edit_title_new
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactEditSheet(
    editing: ContactBookEntry?,
    onAction: (ContactBookUiAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(editing?.toDraft() ?: ContactDraft()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(
                    if (editing == null) MR.string.contactbook_edit_title_new
                    else MR.string.contactbook_edit_title_edit
                ),
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            Field(draft.givenName, stringResource(MR.string.contactbook_edit_given_name)) {
                draft = draft.copy(givenName = it)
            }
            Field(draft.surname, stringResource(MR.string.contactbook_edit_surname)) {
                draft = draft.copy(surname = it)
            }
            Field(draft.phone, stringResource(MR.string.contactbook_edit_phone)) {
                draft = draft.copy(phone = it)
            }
            Field(draft.email, stringResource(MR.string.contactbook_edit_email)) {
                draft = draft.copy(email = it)
            }
            Field(draft.city, stringResource(MR.string.contactbook_edit_city)) {
                draft = draft.copy(city = it)
            }
            Field(draft.country, stringResource(MR.string.contactbook_edit_country)) {
                draft = draft.copy(country = it)
            }
            Field(draft.birthday, stringResource(MR.string.contactbook_edit_birthday)) {
                draft = draft.copy(birthday = it)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(MR.string.contactbook_edit_cancel))
                }
                Button(
                    onClick = { onAction(ContactBookUiAction.SaveContact(draft, editing)) },
                    enabled = draft.isSavable,
                ) {
                    Text(stringResource(MR.string.contactbook_edit_save))
                }
            }
        }
    }
}

@Composable
private fun Field(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
