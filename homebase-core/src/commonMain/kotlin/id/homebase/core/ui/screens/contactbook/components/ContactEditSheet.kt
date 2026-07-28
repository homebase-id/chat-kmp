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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import id.homebase.api.util.cleanDomain
import id.homebase.core.image.HomebaseImage
import id.homebase.core.ui.screens.contactbook.ContactDraft
import id.homebase.core.ui.screens.contactbook.ContactFieldValidation
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.core.ui.screens.contactbook.syncedDraft
import id.homebase.core.ui.screens.contactbook.toDraft
import id.homebase.core.widget.HomebaseIdField
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
import id.homebase.resources.contactbook_error_birthday
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
    onSave: (ContactDraft, List<String>, List<String>, PlatformFile?) -> Unit,
    onDismiss: () -> Unit,
    odinIdLocked: Boolean = false,
) {
    var draft by remember { mutableStateOf(editing?.toDraft() ?: ContactDraft()) }
    // Extra phones/emails beyond the single canonical slot — app-local additions (see overlay).
    // Phones carry a stable id so the stateful PhoneNumberField rows keep their seeded country/
    // national state when a row above them is removed (index-keying would shuffle that state).
    var addPhones by remember {
        mutableStateOf(editing?.additionalPhones.orEmpty().mapIndexed { i, v -> DraftPhone(i, v) })
    }
    var nextPhoneId by remember { mutableStateOf(editing?.additionalPhones.orEmpty().size) }
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
            val odinIdErrorText = stringResource(MR.string.contactbook_error_odinid)
            val odinIdShowError = !draft.odinIdValid && draft.odinId.isNotBlank()
            // Local TextFieldValue stores space-encoded text; HomebaseIdField's visual
            // transformation renders those spaces as dots. Sheet is composed fresh on each
            // open, so `remember` re-seeding from draft.odinId is fine.
            var odinIdField by remember {
                mutableStateOf(
                    TextFieldValue(
                        text = draft.odinId.replace('.', ' '),
                        selection = TextRange(draft.odinId.length),
                    )
                )
            }
            HomebaseIdField(
                value = odinIdField,
                onValueChange = { incoming ->
                    val normalizedSpaces = incoming.text.cleanDomain().replace('.', ' ')
                    odinIdField = incoming.copy(text = normalizedSpaces)
                    draft = draft.copy(
                        odinId = normalizedSpaces.cleanDomain(preserveTrailingDot = false, preserveTrailingDash = false),
                    )
                },
                label = { Text(stringResource(MR.string.contactbook_edit_odinid)) },
                supportingText = when {
                    odinIdShowError -> { { Text(odinIdErrorText) } }
                    odinIdLockNote != null -> { { Text(odinIdLockNote) } }
                    else -> null
                },
                trailingIcon = if (odinIdLocked) {
                    { Icon(Icons.Outlined.Lock, contentDescription = odinIdLockNote) }
                } else null,
                isError = odinIdShowError,
                readOnly = odinIdLocked,
            )
            val resetDesc = stringResource(MR.string.contactbook_edit_reset)
            // PhoneNumberField owns its national/country state after seeding, so reset re-keys it.
            var phoneSeed by remember { mutableStateOf(0) }
            val phoneOverridden =
                isIdentity && synced?.phone.orEmpty().trim() != draft.phone.trim()
            val phoneErrorText = stringResource(MR.string.contactbook_error_phone)
            key(phoneSeed) {
                PhoneNumberField(
                    e164Value = draft.phone,
                    onValueChange = { draft = draft.copy(phone = it) },
                    label = stringResource(MR.string.contactbook_edit_phone),
                    isError = draft.phone.isNotBlank() && !draft.phoneValid,
                    errorText = phoneErrorText,
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
            // Additional phones use the same E.164 control + validation as the primary number.
            addPhones.forEachIndexed { i, entry ->
                key(entry.id) {
                    PhoneNumberField(
                        e164Value = entry.value,
                        onValueChange = { addPhones = addPhones.replaceAt(i, entry.copy(value = it)) },
                        label = stringResource(MR.string.contactbook_edit_phone),
                        isError = entry.value.isNotBlank() &&
                            !ContactFieldValidation.isValidPhone(entry.value),
                        errorText = phoneErrorText,
                        trailingIcon = {
                            IconButton(onClick = { addPhones = addPhones.filterNot { it.id == entry.id } }) {
                                Icon(Icons.Outlined.Close, contentDescription = removeDesc)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
            }
            AddMoreButton(stringResource(MR.string.contactbook_edit_add_phone)) {
                addPhones = addPhones + DraftPhone(nextPhoneId++, "")
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
            val emailErrorText = stringResource(MR.string.contactbook_error_email)
            addEmails.forEachIndexed { i, value ->
                Field(
                    value = value,
                    label = stringResource(MR.string.contactbook_edit_email),
                    isError = !ContactFieldValidation.isValidEmail(value),
                    errorText = emailErrorText,
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
                isError = !draft.birthdayValid,
                errorText = stringResource(MR.string.contactbook_error_birthday),
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
                // Save requires at least one meaningful field AND every phone/email — primary and
                // additional — plus the birthday to be well-formed (E.164 / valid email /
                // ISO date). Legacy bad data stays visible but blocks Save until corrected.
                val hasContent = draft.givenName.isNotBlank() || draft.surname.isNotBlank() ||
                    draft.phone.isNotBlank() || draft.email.isNotBlank() || draft.odinId.isNotBlank() ||
                    addPhones.any { it.value.isNotBlank() } || addEmails.any { it.isNotBlank() }
                val primaryValid = draft.phoneValid && draft.emailValid && draft.odinIdValid &&
                    draft.birthdayValid
                val additionsValid = addPhones.all { ContactFieldValidation.isValidPhone(it.value) } &&
                    addEmails.all { ContactFieldValidation.isValidEmail(it) }
                Button(
                    onClick = { onSave(draft, addPhones.map { it.value }, addEmails, photo) },
                    enabled = hasContent && primaryValid && additionsValid,
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

/** An additional phone row with a stable id, so the stateful [PhoneNumberField] keeps its seeded
 *  country/national state across insertions and removals of sibling rows. */
private data class DraftPhone(val id: Int, val value: String)

private fun List<DraftPhone>.replaceAt(index: Int, value: DraftPhone): List<DraftPhone> =
    toMutableList().also { it[index] = value }

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
