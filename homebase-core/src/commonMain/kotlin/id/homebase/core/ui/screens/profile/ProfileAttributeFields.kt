@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalUuidApi::class,
    ExperimentalAnimationApi::class,
    ExperimentalLayoutApi::class,
)

package id.homebase.core.ui.screens.profile
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.ExperimentalUuidApi
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import id.homebase.core.util.formatMediumDate
import id.homebase.resources.ok
import id.homebase.resources.profile_edit_label
import id.homebase.resources.profile_edit_birthday_pick
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.style.TextDirection
import id.homebase.resources.profile_edit_label_custom
import id.homebase.resources.profile_edit_label_home
import id.homebase.resources.profile_edit_label_mobile
import id.homebase.resources.profile_edit_label_personal
import id.homebase.resources.profile_edit_label_work
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.core.ui.screens.card.CookieBadge
import id.homebase.core.ui.screens.card.WithTooltip
import id.homebase.core.ui.screens.contactbook.ContactFieldValidation
import id.homebase.core.ui.screens.contactbook.components.PhoneNumberField
import id.homebase.core.widget.AdaptiveSheet
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.contactbook_detail_location
import id.homebase.resources.contactbook_detail_name
import id.homebase.resources.contactbook_error_birthday
import id.homebase.resources.contactbook_error_email
import id.homebase.resources.contactbook_error_phone
import id.homebase.resources.profile_edit_add_attribute_title
import id.homebase.resources.profile_edit_additional_name
import id.homebase.resources.profile_edit_address1
import id.homebase.resources.profile_edit_address2
import id.homebase.resources.profile_edit_address_label
import id.homebase.resources.profile_edit_bio
import id.homebase.resources.profile_edit_birthday
import id.homebase.resources.profile_edit_city
import id.homebase.resources.profile_edit_conflict_detail
import id.homebase.resources.profile_edit_conflict_remove
import id.homebase.resources.profile_edit_conflict_title
import id.homebase.resources.profile_edit_country
import id.homebase.resources.profile_edit_email
import id.homebase.resources.profile_edit_email_label
import id.homebase.resources.profile_edit_facebook
import id.homebase.resources.profile_edit_given_name
import id.homebase.resources.profile_edit_instagram
import id.homebase.resources.profile_edit_linkedin
import id.homebase.resources.profile_edit_nickname
import id.homebase.resources.profile_edit_phone
import id.homebase.resources.profile_edit_phone_label
import id.homebase.resources.profile_edit_postcode
import id.homebase.resources.profile_edit_status
import id.homebase.resources.profile_edit_surname
import id.homebase.resources.profile_edit_tiktok
import id.homebase.resources.profile_edit_twitter
import id.homebase.resources.remove
import id.homebase.resources.save
import kotlinx.coroutines.flow.first

@Composable
internal fun EditorActions(
    canSave: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onRemove: (() -> Unit)?,
    removeLabel: String = stringResource(MR.string.remove),
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onRemove != null) {
            WithTooltip(removeLabel) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Outlined.Delete, contentDescription = removeLabel, tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(MR.string.cancel))
        }
        Spacer(Modifier.width(8.dp))
        Button(enabled = canSave, onClick = onSave, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(MR.string.save), maxLines = 1, softWrap = false)
        }
    }
}


/** What a record that disagrees with the edited one holds, for showing the user before they discard it. */
internal fun conflictValue(type: String, record: ProfileAttribute): String =
    PROFILE_TYPE_FIELDS[type].orEmpty()
        .mapNotNull { (_, key) -> record.string(key)?.ifBlank { null } }
        .joinToString(", ")

@Composable
internal fun ConflictRow(value: String, audience: String, onDiscard: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(MR.string.profile_edit_conflict_title, value),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(MR.string.profile_edit_conflict_detail, audience),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onDiscard) { Text(stringResource(MR.string.profile_edit_conflict_remove)) }
    }
}

@Composable
internal fun ProfileField(
    value: String,
    label: String,
    showLabel: Boolean = true,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
    errorText: String? = null,
    minLines: Int = 1,
    maxLines: Int = 1,
    ltr: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        // Addresses and URLs read left to right in every locale.
        textStyle = if (ltr) LocalTextStyle.current.copy(textDirection = TextDirection.Ltr) else LocalTextStyle.current,
        label = if (showLabel) { { Text(label) } } else null,
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = maxLines == 1,
        minLines = minLines,
        maxLines = maxLines,
        isError = isError,
        supportingText = if (isError && errorText != null) { { Text(errorText) } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        // The panel header names a lone field, so the label moves into its semantics.
        modifier = if (showLabel) modifier else modifier.semantics { contentDescription = label },
    )
}

/** One attribute type the "add detail" sheet can offer; its editable fields live in [AttributeFields]. */
internal data class AttributeSpec(
    val type: String,
    val icon: ImageVector,
    val labelRes: StringResource,
    val social: Boolean = false,
)

internal val ATTRIBUTE_SPECS = listOf(
    AttributeSpec(ProfileAttributeTypes.NAME, Icons.Outlined.Person, MR.string.contactbook_detail_name),
    AttributeSpec(ProfileAttributeTypes.NICKNAME, Icons.Outlined.Badge, MR.string.profile_edit_nickname),
    AttributeSpec(ProfileAttributeTypes.STATUS, Icons.Outlined.Mood, MR.string.profile_edit_status),
    AttributeSpec(ProfileAttributeTypes.BIRTHDAY, Icons.Outlined.Cake, MR.string.profile_edit_birthday),
    AttributeSpec(ProfileAttributeTypes.BIO_SUMMARY, Icons.AutoMirrored.Outlined.Notes, MR.string.profile_edit_bio),
    AttributeSpec(ProfileAttributeTypes.EMAIL, Icons.Outlined.Email, MR.string.profile_edit_email),
    AttributeSpec(ProfileAttributeTypes.PHONE, Icons.Outlined.Call, MR.string.profile_edit_phone),
    AttributeSpec(ProfileAttributeTypes.ADDRESS, Icons.Outlined.LocationOn, MR.string.contactbook_detail_location),
    AttributeSpec(ProfileAttributeTypes.TWITTER, Icons.Outlined.Tag, MR.string.profile_edit_twitter, social = true),
    AttributeSpec(ProfileAttributeTypes.FACEBOOK, Icons.Outlined.ThumbUp, MR.string.profile_edit_facebook, social = true),
    AttributeSpec(ProfileAttributeTypes.INSTAGRAM, Icons.Outlined.PhotoCamera, MR.string.profile_edit_instagram, social = true),
    AttributeSpec(ProfileAttributeTypes.TIKTOK, Icons.Outlined.MusicNote, MR.string.profile_edit_tiktok, social = true),
    AttributeSpec(ProfileAttributeTypes.LINKEDIN, Icons.Outlined.WorkOutline, MR.string.profile_edit_linkedin, social = true),
)


@Composable
internal fun AddAttributeSheet(
    missing: List<AttributeSpec>,
    onPick: (AttributeSpec) -> Unit,
    onDismiss: () -> Unit,
) {
    AdaptiveSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(MR.string.profile_edit_add_attribute_title),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
            )
            missing.forEach { spec ->
                val colors = MaterialTheme.colorScheme
                ListItem(
                    leadingContent = {
                        CookieBadge(
                            icon = spec.icon,
                            container = colors.secondaryContainer,
                            content = colors.onSecondaryContainer,
                            size = 44.dp,
                            iconSize = 22.dp,
                        )
                    },
                    headlineContent = { Text(stringResource(spec.labelRes)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth().clickable { dismiss { onPick(spec) } }.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

/** Whether [type]'s current draft is well-formed enough to save — only Email/Phone/Birthday
 *  constrain format; every other attribute type accepts anything (including blank, which just
 *  no-ops). */
internal fun isAttributeValid(type: String, value: (ProfileField) -> String): Boolean = when (type) {
    ProfileAttributeTypes.EMAIL -> ContactFieldValidation.isValidEmail(value(ProfileField.EMAIL))
    ProfileAttributeTypes.PHONE -> ContactFieldValidation.isValidPhone(value(ProfileField.PHONE))
    ProfileAttributeTypes.BIRTHDAY ->
        ContactFieldValidation.isValidBirthday(value(ProfileField.BIRTHDAY))
    else -> true
}

/**
 * The editable field(s) for one attribute type, bound generically via [value]/[onChange] so both an
 * existing row's inline editor and a newly added one share the exact same field UI.
 */
@Composable
internal fun AttributeFields(
    type: String,
    value: (ProfileField) -> String,
    onChange: (ProfileField, String) -> Unit,
) {
    when (type) {
        ProfileAttributeTypes.NAME -> {
            ProfileField(
                value(ProfileField.GIVEN_NAME),
                stringResource(MR.string.profile_edit_given_name),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.GIVEN_NAME, it) }
            ProfileField(
                value(ProfileField.SURNAME),
                stringResource(MR.string.profile_edit_surname),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.SURNAME, it) }
            ProfileField(
                value(ProfileField.ADDITIONAL_NAME),
                stringResource(MR.string.profile_edit_additional_name),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.ADDITIONAL_NAME, it) }
        }

        ProfileAttributeTypes.NICKNAME -> {
            ProfileField(
                value(ProfileField.NICKNAME),
                stringResource(MR.string.profile_edit_nickname),
                showLabel = false,
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.NICKNAME, it) }
        }

        ProfileAttributeTypes.STATUS -> {
            ProfileField(
                value(ProfileField.STATUS),
                stringResource(MR.string.profile_edit_status),
                showLabel = false,
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.STATUS, it) }
        }

        ProfileAttributeTypes.BIO_SUMMARY -> {
            ProfileField(
                value(ProfileField.BIO),
                stringResource(MR.string.profile_edit_bio),
                showLabel = false,
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.BIO, it) }
        }

        ProfileAttributeTypes.BIRTHDAY -> {
            BirthdayField(value(ProfileField.BIRTHDAY)) { onChange(ProfileField.BIRTHDAY, it) }
        }

        ProfileAttributeTypes.EMAIL -> {
            val emailValue = value(ProfileField.EMAIL)
            ProfileField(
                value = emailValue,
                label = stringResource(MR.string.profile_edit_email),
                showLabel = false,
                keyboardType = KeyboardType.Email,
                ltr = true,
                isError = emailValue.isNotBlank() && !ContactFieldValidation.isValidEmail(emailValue),
                errorText = stringResource(MR.string.contactbook_error_email),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.EMAIL, it) }
            LabelChips(
                value = value(ProfileField.EMAIL_LABEL),
                presets = listOf(MR.string.profile_edit_label_personal, MR.string.profile_edit_label_work),
                customLabel = stringResource(MR.string.profile_edit_email_label),
            ) { onChange(ProfileField.EMAIL_LABEL, it) }
        }

        ProfileAttributeTypes.PHONE -> {
            val phoneValue = value(ProfileField.PHONE)
            val phoneLabel = stringResource(MR.string.profile_edit_phone)
            PhoneNumberField(
                e164Value = phoneValue,
                onValueChange = { onChange(ProfileField.PHONE, it) },
                label = null,
                isError = phoneValue.isNotBlank() && !ContactFieldValidation.isValidPhone(phoneValue),
                errorText = stringResource(MR.string.contactbook_error_phone),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = phoneLabel },
            )
            LabelChips(
                value = value(ProfileField.PHONE_LABEL),
                presets = listOf(MR.string.profile_edit_label_mobile, MR.string.profile_edit_label_home, MR.string.profile_edit_label_work),
                customLabel = stringResource(MR.string.profile_edit_phone_label),
            ) { onChange(ProfileField.PHONE_LABEL, it) }
        }

        ProfileAttributeTypes.ADDRESS -> {
            LabelChips(
                value = value(ProfileField.ADDRESS_LABEL),
                presets = listOf(MR.string.profile_edit_label_home, MR.string.profile_edit_label_work),
                customLabel = stringResource(MR.string.profile_edit_address_label),
            ) { onChange(ProfileField.ADDRESS_LABEL, it) }
            ProfileField(
                value = value(ProfileField.ADDRESS1),
                label = stringResource(MR.string.profile_edit_address1),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.ADDRESS1, it) }
            ProfileField(
                value = value(ProfileField.ADDRESS2),
                label = stringResource(MR.string.profile_edit_address2),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.ADDRESS2, it) }
            ProfileField(
                value = value(ProfileField.POSTCODE),
                label = stringResource(MR.string.profile_edit_postcode),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.POSTCODE, it) }
            ProfileField(
                value = value(ProfileField.CITY),
                label = stringResource(MR.string.profile_edit_city),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.CITY, it) }
            ProfileField(
                value = value(ProfileField.COUNTRY),
                label = stringResource(MR.string.profile_edit_country),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.COUNTRY, it) }
        }

        ProfileAttributeTypes.TWITTER -> {
            ProfileField(
                value(ProfileField.TWITTER),
                stringResource(MR.string.profile_edit_twitter),
                showLabel = false,
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.TWITTER, it) }
        }
        ProfileAttributeTypes.FACEBOOK -> {
            ProfileField(
                value(ProfileField.FACEBOOK),
                stringResource(MR.string.profile_edit_facebook),
                showLabel = false,
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.FACEBOOK, it) }
        }
        ProfileAttributeTypes.INSTAGRAM -> {
            ProfileField(
                value(ProfileField.INSTAGRAM),
                stringResource(MR.string.profile_edit_instagram),
                showLabel = false,
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.INSTAGRAM, it) }
        }
        ProfileAttributeTypes.TIKTOK -> {
            ProfileField(
                value(ProfileField.TIKTOK),
                stringResource(MR.string.profile_edit_tiktok),
                showLabel = false,
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.TIKTOK, it) }
        }
        ProfileAttributeTypes.LINKEDIN -> {
            ProfileField(
                value(ProfileField.LINKEDIN),
                stringResource(MR.string.profile_edit_linkedin),
                showLabel = false,
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.LINKEDIN, it) }
        }
    }
}

/**
 * A detail's label as one tap: a connected group of presets plus Custom, which opens a field.
 * Tapping the picked segment again clears the label.
 */
@Composable
private fun LabelChips(
    value: String,
    presets: List<StringResource>,
    customLabel: String,
    onChange: (String) -> Unit,
) {
    val names = presets.map { stringResource(it) }
    var custom by remember { mutableStateOf(value.isNotBlank() && names.none { it.equals(value, ignoreCase = true) }) }
    var focusCustom by remember { mutableStateOf(false) }
    val customField = remember { FocusRequester() }
    val motion = MaterialTheme.motionScheme
    val colors = ToggleButtonDefaults.toggleButtonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChoiceCaption(stringResource(MR.string.profile_edit_label))
        ConnectedChoices(count = names.size + 1) { index, shapes, sizing ->
            val isCustom = index == names.size
            val picked = if (isCustom) custom else !custom && names[index].equals(value, ignoreCase = true)
            ToggleButton(
                checked = picked,
                onCheckedChange = {
                    if (isCustom) {
                        custom = !custom
                        focusCustom = custom
                        if (!custom || names.any { it.equals(value, ignoreCase = true) }) onChange("")
                    } else {
                        custom = false
                        onChange(if (picked) "" else names[index])
                    }
                },
                shapes = shapes,
                colors = colors,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = sizing.semantics { role = Role.RadioButton },
            ) {
                Text(
                    text = if (isCustom) stringResource(MR.string.profile_edit_label_custom) else names[index],
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AnimatedVisibility(
            visible = custom,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.defaultSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            // Focused once fully open, so it lands above the keyboard rather than growing under it.
            LaunchedEffect(Unit) {
                if (!focusCustom) return@LaunchedEffect
                snapshotFlow { transition.currentState == EnterExitState.Visible }.first { it }
                customField.requestFocus()
                focusCustom = false
            }
            ProfileField(value = value, label = customLabel, modifier = Modifier.fillMaxWidth().focusRequester(customField), onChange = onChange)
        }
    }
}

/** The ISO birthday as the locale writes dates; null when it is not a valid date. */
internal fun formatBirthday(iso: String): String? {
    val date = runCatching { LocalDate.parse(iso.trim()) }.getOrNull() ?: return null
    // Noon keeps any time zone from turning the day over.
    return formatMediumDate(date.atTime(12, 0).toInstant(TimeZone.currentSystemDefault()))
}

/** A birthday is picked from the calendar, never typed; a stored value that is not a date still shows, flagged. */
@Composable
private fun BirthdayField(value: String, onChange: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val label = stringResource(MR.string.profile_edit_birthday)
    val invalid = value.isNotBlank() && !ContactFieldValidation.isValidBirthday(value)
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = if (value.isBlank()) "" else formatBirthday(value) ?: value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            placeholder = { Text(stringResource(MR.string.profile_edit_birthday_pick)) },
            leadingIcon = { Icon(Icons.Outlined.CalendarMonth, contentDescription = null) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            isError = invalid,
            supportingText = if (invalid) { { Text(stringResource(MR.string.contactbook_error_birthday)) } } else null,
            modifier = Modifier.fillMaxWidth(),
        )
        // Over the whole field, so any tap opens the calendar instead of placing a cursor.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(MaterialTheme.shapes.extraSmall)
                .clickable(role = Role.Button, onClickLabel = label) { picking = true }
                .semantics { contentDescription = label },
        )
    }
    if (picking) {
        val initial = remember(value) {
            runCatching { LocalDate.parse(value.trim()) }.getOrNull()
                ?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds()
        }
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        // The picker answers in UTC midnight of the picked day.
                        state.selectedDateMillis?.let {
                            onChange(Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date.toString())
                        }
                        picking = false
                    },
                ) { Text(stringResource(MR.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }) { Text(stringResource(MR.string.cancel)) }
            },
        ) {
            DatePicker(state = state)
        }
    }
}
