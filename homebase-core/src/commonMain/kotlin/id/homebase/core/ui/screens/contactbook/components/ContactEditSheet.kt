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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import id.homebase.core.image.HomebaseImage
import id.homebase.core.ui.screens.contactbook.ContactDraft
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.core.ui.screens.contactbook.syncedDraft
import id.homebase.core.ui.screens.contactbook.toDraft
import id.homebase.resources.MR
import id.homebase.resources.contactbook_edit_add_email
import id.homebase.resources.contactbook_edit_add_phone
import id.homebase.resources.contactbook_edit_birthday
import id.homebase.resources.contactbook_edit_cancel
import id.homebase.resources.contactbook_edit_change_photo
import id.homebase.resources.contactbook_edit_city
import id.homebase.resources.contactbook_edit_country
import id.homebase.resources.contactbook_edit_email
import id.homebase.resources.contactbook_edit_given_name
import id.homebase.resources.contactbook_edit_odinid
import id.homebase.resources.contactbook_edit_odinid_locked
import id.homebase.resources.contactbook_edit_override_added
import id.homebase.resources.contactbook_edit_overrides
import id.homebase.resources.contactbook_edit_phone
import id.homebase.resources.contactbook_edit_remove
import id.homebase.resources.contactbook_edit_reset
import id.homebase.resources.contactbook_edit_save
import id.homebase.resources.contactbook_edit_synced_banner
import id.homebase.resources.contactbook_edit_synced_from_profile
import id.homebase.resources.contactbook_edit_surname
import id.homebase.resources.contactbook_edit_title_edit
import id.homebase.resources.contactbook_edit_title_new
import id.homebase.resources.contactbook_error_email
import id.homebase.resources.contactbook_error_odinid
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.readBytes
import org.jetbrains.compose.resources.stringResource

@Composable
fun ContactEditSheet(
    editing: ContactBookEntry?,
    onSave: (ContactDraft, List<String>, List<String>, PlatformFile?) -> Unit,
    onDismiss: () -> Unit,
    odinIdLocked: Boolean = false,
) {
    var draft by remember { mutableStateOf(editing?.toDraft() ?: ContactDraft()) }
    // Extra phones/emails beyond the single canonical slot — app-local additions (see overlay).
    var addPhones by remember { mutableStateOf(editing?.additionalPhones.orEmpty()) }
    var addEmails by remember { mutableStateOf(editing?.additionalEmails.orEmpty()) }
    // Identity contact (has odinId): fields are synced from the profile and edits become private
    // overrides. `synced` is the pre-override baseline used for the per-field affordance.
    val isIdentity = editing != null && !editing.odinId.isNullOrBlank()
    val synced = remember(editing) { editing?.syncedDraft() }
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

            // Identity contacts: name/phone/email/etc. are synced from their Homebase profile;
            // editing writes a private app-local override. Make that explicit (banner + per-field
            // "from their profile" / "overrides …" supporting text + reset). Manual contacts edit
            // their own data plainly.
            if (isIdentity) {
                SyncedBanner()
                Spacer(modifier = Modifier.height(4.dp))
            }

            SyncedField(
                value = draft.givenName,
                synced = if (isIdentity) synced?.givenName else null,
                label = stringResource(MR.string.contactbook_edit_given_name),
                onReset = { draft = draft.copy(givenName = synced?.givenName.orEmpty()) },
            ) { draft = draft.copy(givenName = it) }
            SyncedField(
                value = draft.surname,
                synced = if (isIdentity) synced?.surname else null,
                label = stringResource(MR.string.contactbook_edit_surname),
                onReset = { draft = draft.copy(surname = synced?.surname.orEmpty()) },
            ) { draft = draft.copy(surname = it) }
            val odinIdLockNote =
                if (odinIdLocked) stringResource(MR.string.contactbook_edit_odinid_locked) else null
            Field(
                value = draft.odinId,
                label = stringResource(MR.string.contactbook_edit_odinid),
                isError = !draft.odinIdValid,
                errorText = stringResource(MR.string.contactbook_error_odinid),
                keyboardType = KeyboardType.Uri,
                readOnly = odinIdLocked,
                helperText = odinIdLockNote,
                trailingIcon = if (odinIdLocked) {
                    { Icon(Icons.Outlined.Lock, contentDescription = odinIdLockNote) }
                } else null,
            ) { draft = draft.copy(odinId = it) }
            val resetDesc = stringResource(MR.string.contactbook_edit_reset)
            // PhoneNumberField owns its national/country state after seeding, so reset re-keys it.
            var phoneSeed by remember { mutableStateOf(0) }
            val phoneOverridden =
                isIdentity && synced?.phone.orEmpty().trim() != draft.phone.trim()
            key(phoneSeed) {
                PhoneNumberField(
                    e164Value = draft.phone,
                    onValueChange = { draft = draft.copy(phone = it) },
                    label = stringResource(MR.string.contactbook_edit_phone),
                    supportingText = if (isIdentity) {
                        syncedSupportingText(draft.phone, synced?.phone)
                    } else null,
                    trailingIcon = if (phoneOverridden) {
                        {
                            IconButton(onClick = {
                                draft = draft.copy(phone = synced?.phone.orEmpty())
                                phoneSeed++
                            }) { Icon(Icons.Outlined.Restore, contentDescription = resetDesc) }
                        }
                    } else null,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
            val removeDesc = stringResource(MR.string.contactbook_edit_remove)
            addPhones.forEachIndexed { i, value ->
                Field(
                    value = value,
                    label = stringResource(MR.string.contactbook_edit_phone),
                    keyboardType = KeyboardType.Phone,
                    trailingIcon = {
                        IconButton(onClick = { addPhones = addPhones.removeAt(i) }) {
                            Icon(Icons.Outlined.Close, contentDescription = removeDesc)
                        }
                    },
                ) { addPhones = addPhones.replaceAt(i, it) }
            }
            AddMoreButton(stringResource(MR.string.contactbook_edit_add_phone)) {
                addPhones = addPhones + ""
            }
            SyncedField(
                value = draft.email,
                synced = if (isIdentity) synced?.email else null,
                label = stringResource(MR.string.contactbook_edit_email),
                isError = !draft.emailValid,
                errorText = stringResource(MR.string.contactbook_error_email),
                keyboardType = KeyboardType.Email,
                onReset = { draft = draft.copy(email = synced?.email.orEmpty()) },
            ) { draft = draft.copy(email = it) }
            addEmails.forEachIndexed { i, value ->
                Field(
                    value = value,
                    label = stringResource(MR.string.contactbook_edit_email),
                    keyboardType = KeyboardType.Email,
                    trailingIcon = {
                        IconButton(onClick = { addEmails = addEmails.removeAt(i) }) {
                            Icon(Icons.Outlined.Close, contentDescription = removeDesc)
                        }
                    },
                ) { addEmails = addEmails.replaceAt(i, it) }
            }
            AddMoreButton(stringResource(MR.string.contactbook_edit_add_email)) {
                addEmails = addEmails + ""
            }
            SyncedField(
                value = draft.city,
                synced = if (isIdentity) synced?.city else null,
                label = stringResource(MR.string.contactbook_edit_city),
                onReset = { draft = draft.copy(city = synced?.city.orEmpty()) },
            ) { draft = draft.copy(city = it) }
            SyncedField(
                value = draft.country,
                synced = if (isIdentity) synced?.country else null,
                label = stringResource(MR.string.contactbook_edit_country),
                onReset = { draft = draft.copy(country = synced?.country.orEmpty()) },
            ) { draft = draft.copy(country = it) }
            SyncedField(
                value = draft.birthday,
                synced = if (isIdentity) synced?.birthday else null,
                label = stringResource(MR.string.contactbook_edit_birthday),
                onReset = { draft = draft.copy(birthday = synced?.birthday.orEmpty()) },
            ) { draft = draft.copy(birthday = it) }

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(MR.string.contactbook_edit_cancel))
                }
                // Savable if the primary draft is valid OR there's at least one additional
                // phone/email — so adding only an extra contact method still enables Save.
                val hasAdditions = addPhones.any { it.isNotBlank() } || addEmails.any { it.isNotBlank() }
                Button(
                    onClick = { onSave(draft, addPhones, addEmails, photo) },
                    enabled = draft.isSavable || hasAdditions,
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
private fun AddMoreButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.Add, contentDescription = null)
        Spacer(modifier = Modifier.size(8.dp))
        Text(label)
    }
}

/** Banner atop the editor for an identity contact, explaining synced-vs-private-override. */
@Composable
private fun SyncedBanner() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(MR.string.contactbook_edit_synced_banner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A text field that, for an identity contact ([synced] non-null), shows whether its value comes
 * from the profile or is a private override, with a reset-to-profile action once overridden. For a
 * manual contact ([synced] null) it's a plain [Field].
 */
@Composable
private fun SyncedField(
    value: String,
    synced: String?,
    label: String,
    isError: Boolean = false,
    errorText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    onReset: () -> Unit,
    onChange: (String) -> Unit,
) {
    if (synced == null) {
        Field(value, label, isError, errorText, keyboardType, onChange = onChange)
        return
    }
    val overridden = synced.trim() != value.trim()
    val resetDesc = stringResource(MR.string.contactbook_edit_reset)
    Field(
        value = value,
        label = label,
        isError = isError,
        errorText = errorText,
        keyboardType = keyboardType,
        helperText = syncedSupportingText(value, synced),
        trailingIcon = if (overridden) {
            { IconButton(onClick = onReset) { Icon(Icons.Outlined.Restore, contentDescription = resetDesc) } }
        } else {
            null
        },
        onChange = onChange,
    )
}

/** Supporting line for a synced field: from-profile, overriding-a-value, or your-own-addition. */
@Composable
private fun syncedSupportingText(value: String, synced: String?): String? {
    val s = synced.orEmpty()
    val overridden = s.trim() != value.trim()
    return when {
        overridden && s.isNotBlank() -> stringResource(MR.string.contactbook_edit_overrides, s)
        overridden -> stringResource(MR.string.contactbook_edit_override_added)
        s.isNotBlank() -> stringResource(MR.string.contactbook_edit_synced_from_profile)
        else -> null
    }
}

private fun List<String>.replaceAt(index: Int, value: String): List<String> =
    toMutableList().also { it[index] = value }

private fun List<String>.removeAt(index: Int): List<String> =
    filterIndexed { i, _ -> i != index }

@Composable
private fun Field(
    value: String,
    label: String,
    isError: Boolean = false,
    errorText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    readOnly: Boolean = false,
    helperText: String? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    onChange: (String) -> Unit,
) {
    val showError = isError && value.isNotBlank()
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = showError,
        readOnly = readOnly,
        trailingIcon = trailingIcon,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        supportingText = when {
            showError && errorText != null -> { { Text(errorText) } }
            helperText != null -> { { Text(helperText) } }
            else -> null
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
