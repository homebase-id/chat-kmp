package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import id.homebase.core.image.HomebaseImage
import id.homebase.core.ui.screens.contactbook.ContactBookUiAction
import id.homebase.core.ui.screens.contactbook.ContactDraft
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.core.ui.screens.contactbook.toDraft
import id.homebase.resources.MR
import id.homebase.resources.contactbook_edit_birthday
import id.homebase.resources.contactbook_edit_cancel
import id.homebase.resources.contactbook_edit_change_photo
import id.homebase.resources.contactbook_edit_city
import id.homebase.resources.contactbook_edit_country
import id.homebase.resources.contactbook_edit_email
import id.homebase.resources.contactbook_edit_given_name
import id.homebase.resources.contactbook_edit_odinid
import id.homebase.resources.contactbook_edit_phone
import id.homebase.resources.contactbook_edit_save
import id.homebase.resources.contactbook_edit_surname
import id.homebase.resources.contactbook_edit_title_edit
import id.homebase.resources.contactbook_edit_title_new
import id.homebase.resources.contactbook_error_email
import id.homebase.resources.contactbook_error_odinid
import id.homebase.resources.contactbook_error_phone
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.readBytes
import org.jetbrains.compose.resources.stringResource

@Composable
fun ContactEditSheet(
    editing: ContactBookEntry?,
    onAction: (ContactBookUiAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(editing?.toDraft() ?: ContactDraft()) }
    var photo by remember { mutableStateOf<PlatformFile?>(null) }
    var photoBytes by remember { mutableStateOf<ByteArray?>(null) }

    LaunchedEffect(photo) {
        photoBytes = photo?.let { runCatching { it.readBytes() }.getOrNull() }
    }

    val photoPicker = rememberFilePickerLauncher(
        type = FileKitType.Image,
    ) { file -> if (file != null) photo = file }

    AdaptiveSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(
                    if (editing == null) MR.string.contactbook_edit_title_new
                    else MR.string.contactbook_edit_title_edit
                ),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )

            EditAvatar(editing = editing, photoBytes = photoBytes)
            TextButton(onClick = { photoPicker.launch() }) {
                Icon(Icons.Outlined.AddAPhoto, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(MR.string.contactbook_edit_change_photo))
            }
            Spacer(modifier = Modifier.height(8.dp))

            Field(draft.givenName, stringResource(MR.string.contactbook_edit_given_name)) {
                draft = draft.copy(givenName = it)
            }
            Field(draft.surname, stringResource(MR.string.contactbook_edit_surname)) {
                draft = draft.copy(surname = it)
            }
            Field(
                value = draft.odinId,
                label = stringResource(MR.string.contactbook_edit_odinid),
                isError = !draft.odinIdValid,
                errorText = stringResource(MR.string.contactbook_error_odinid),
                keyboardType = KeyboardType.Uri,
            ) { draft = draft.copy(odinId = it) }
            Field(
                value = draft.phone,
                label = stringResource(MR.string.contactbook_edit_phone),
                isError = !draft.phoneValid,
                errorText = stringResource(MR.string.contactbook_error_phone),
                keyboardType = KeyboardType.Phone,
            ) { draft = draft.copy(phone = it) }
            Field(
                value = draft.email,
                label = stringResource(MR.string.contactbook_edit_email),
                isError = !draft.emailValid,
                errorText = stringResource(MR.string.contactbook_error_email),
                keyboardType = KeyboardType.Email,
            ) { draft = draft.copy(email = it) }
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
                    onClick = { onAction(ContactBookUiAction.SaveContact(draft, editing, photo)) },
                    enabled = draft.isSavable,
                ) {
                    Text(stringResource(MR.string.contactbook_edit_save))
                }
            }
        }
    }
}

@Composable
private fun EditAvatar(editing: ContactBookEntry?, photoBytes: ByteArray?) {
    val size = 88.dp
    when {
        photoBytes != null -> AsyncImage(
            model = photoBytes,
            contentDescription = null,
            modifier = Modifier.size(size).clip(CircleShape),
        )
        editing?.profileImageData() != null -> HomebaseImage(
            imageData = editing.profileImageData()!!,
            modifier = Modifier.size(size).clip(CircleShape),
        )
        else -> Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size / 2),
            )
        }
    }
}

@Composable
private fun Field(
    value: String,
    label: String,
    isError: Boolean = false,
    errorText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    val showError = isError && value.isNotBlank()
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = showError,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        supportingText = if (showError && errorText != null) {
            { Text(errorText) }
        } else null,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
