@file:OptIn(ExperimentalMaterial3Api::class)

package id.homebase.core.ui.screens.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.core.ui.screens.contactbook.ContactFieldValidation
import id.homebase.core.ui.screens.contactbook.components.AdaptiveSheet
import id.homebase.core.ui.screens.contactbook.components.PhoneNumberField
import id.homebase.core.ui.screens.contactbook.components.formatPhoneForDisplay
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.contactbook_detail_location
import id.homebase.resources.contactbook_detail_name
import id.homebase.resources.contactbook_error_email
import id.homebase.resources.contactbook_error_phone
import id.homebase.resources.menu_back
import id.homebase.resources.profile_edit_add_attribute
import id.homebase.resources.profile_edit_add_attribute_title
import id.homebase.resources.profile_edit_additional_name
import id.homebase.resources.profile_edit_address1
import id.homebase.resources.profile_edit_address2
import id.homebase.resources.profile_edit_address_label
import id.homebase.resources.profile_edit_address_label_hint
import id.homebase.resources.profile_edit_birthday
import id.homebase.resources.profile_edit_birthday_hint
import id.homebase.resources.profile_edit_city
import id.homebase.resources.profile_edit_connected_fallback_hint
import id.homebase.resources.profile_edit_country
import id.homebase.resources.profile_edit_email
import id.homebase.resources.profile_edit_email_label
import id.homebase.resources.profile_edit_email_label_hint
import id.homebase.resources.profile_edit_error_forbidden
import id.homebase.resources.profile_edit_error_save
import id.homebase.resources.profile_edit_facebook
import id.homebase.resources.profile_edit_field_not_set
import id.homebase.resources.profile_edit_given_name
import id.homebase.resources.profile_edit_instagram
import id.homebase.resources.profile_edit_linkedin
import id.homebase.resources.profile_edit_load_failed
import id.homebase.resources.profile_edit_nickname
import id.homebase.resources.profile_edit_phone
import id.homebase.resources.profile_edit_phone_label
import id.homebase.resources.profile_edit_phone_label_hint
import id.homebase.resources.profile_edit_postcode
import id.homebase.resources.profile_edit_preview_enter
import id.homebase.resources.profile_edit_preview_exit
import id.homebase.resources.profile_edit_preview_section_public
import id.homebase.resources.profile_edit_preview_section_public_desc
import id.homebase.resources.profile_edit_preview_section_vetted
import id.homebase.resources.profile_edit_preview_section_vetted_desc
import id.homebase.resources.profile_edit_public_hint
import id.homebase.resources.profile_edit_retry
import id.homebase.resources.profile_edit_status
import id.homebase.resources.profile_edit_surname
import id.homebase.resources.profile_edit_tiktok
import id.homebase.resources.profile_edit_title
import id.homebase.resources.profile_edit_twitter
import id.homebase.resources.profile_edit_visibility_connected
import id.homebase.resources.profile_edit_visibility_public
import id.homebase.resources.save
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun ProfileEditScreen(
    viewModel: ProfileEditViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var previewMode by remember { mutableStateOf(false) }

    val errForbidden = stringResource(MR.string.profile_edit_error_forbidden)
    val errSave = stringResource(MR.string.profile_edit_error_save)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ProfileEditEvent.AttributeSaved -> Unit // rows collapse themselves on tap of the checkmark.
                ProfileEditEvent.Back -> onBack()
                ProfileEditEvent.Forbidden -> snackbarHostState.showSnackbar(errForbidden)
                ProfileEditEvent.Error -> snackbarHostState.showSnackbar(errSave)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.profile_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.onAction(ProfileEditAction.BackClicked) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                        )
                    }
                },
                actions = {
                    if (!uiState.isLoading && !uiState.loadFailed) {
                        IconButton(onClick = { previewMode = !previewMode }) {
                            Icon(
                                imageVector = if (previewMode) Icons.Outlined.Edit else Icons.Outlined.Visibility,
                                contentDescription = stringResource(
                                    if (previewMode) MR.string.profile_edit_preview_exit
                                    else MR.string.profile_edit_preview_enter
                                ),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            uiState.isLoading -> LoadingState(Modifier.fillMaxSize().padding(padding))
            uiState.loadFailed -> LoadFailedState(
                modifier = Modifier.fillMaxSize().padding(padding),
                onRetry = { viewModel.onAction(ProfileEditAction.RetryLoadClicked) },
            )
            previewMode -> ProfilePreview(
                uiState = uiState,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            else -> Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (uiState.savingAttributes.isNotEmpty()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                ProfileForm(
                    uiState = uiState,
                    onAction = viewModel::onAction,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun LoadFailedState(modifier: Modifier, onRetry: () -> Unit) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(MR.string.profile_edit_load_failed),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRetry) {
            Text(stringResource(MR.string.profile_edit_retry))
        }
    }
}

/**
 * Mirrors [ProfilePreview]'s Public/Vetted split: everyone-visible fields first, then everything a
 * vetted (connected) contact can see. There's no screen-wide Save — tapping a row expands it in
 * place with a Public|Vetted toggle (defaulting to that row's own section) and its field(s); the
 * checkmark persists just that one attribute at whichever tier is currently selected.
 */
@Composable
private fun ProfileForm(
    uiState: ProfileEditUiState,
    onAction: (ProfileEditAction) -> Unit,
    modifier: Modifier,
) {
    // Which (section tier, attribute type) rows are currently expanded — keeps a row mounted (and
    // visible) for the whole edit session once it has a value. Blank attributes are never opened
    // this way — they're only added via the FAB's dialog, below.
    val editingRows = remember { mutableStateMapOf<Pair<ProfileVisibility, String>, Boolean>() }
    var showAddSheet by remember { mutableStateOf(false) }
    var addDialogTarget by remember { mutableStateOf<Pair<AttributeSpec, ProfileVisibility>?>(null) }

    val missingPublic = ATTRIBUTE_SPECS.filter { displayValueFor(it.type, uiState.anonymousValues) == null }
    val missingVetted = ATTRIBUTE_SPECS.filter { displayValueFor(it.type, uiState.connectedValues) == null }
    val hasMissing = missingPublic.isNotEmpty() || missingVetted.isNotEmpty()

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding(),
        ) {
            Spacer(Modifier.height(8.dp))
            ProfileFieldsSection(
                tier = ProfileVisibility.ANONYMOUS,
                title = stringResource(MR.string.profile_edit_preview_section_public),
                description = stringResource(MR.string.profile_edit_preview_section_public_desc),
                uiState = uiState,
                onAction = onAction,
                editingRows = editingRows,
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp))

            ProfileFieldsSection(
                tier = ProfileVisibility.CONNECTED,
                title = stringResource(MR.string.profile_edit_preview_section_vetted),
                description = stringResource(MR.string.profile_edit_preview_section_vetted_desc),
                uiState = uiState,
                onAction = onAction,
                editingRows = editingRows,
            )
            // Clearance so the last row isn't hidden behind the floating action button.
            Spacer(Modifier.height(88.dp))
        }

        if (hasMissing) {
            FloatingActionButton(
                onClick = { showAddSheet = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(MR.string.profile_edit_add_attribute),
                )
            }
        }
    }

    if (showAddSheet) {
        AddAttributeSheet(
            missingPublic = missingPublic,
            missingVetted = missingVetted,
            onPick = { spec, tier ->
                showAddSheet = false
                addDialogTarget = spec to tier
            },
            onDismiss = { showAddSheet = false },
        )
    }

    addDialogTarget?.let { (spec, tier) ->
        AddAttributeDialog(
            spec = spec,
            initialTier = tier,
            onSave = { savedTier, values ->
                ProfileEditViewModel.TYPE_FIELDS[spec.type].orEmpty().forEach { (field, _) ->
                    onAction(ProfileEditAction.FieldChanged(field, savedTier, values[field].orEmpty()))
                }
                onAction(ProfileEditAction.SaveAttribute(spec.type, savedTier))
                addDialogTarget = null
            },
            onDismiss = { addDialogTarget = null },
        )
    }
}

@Composable
private fun ProfileFieldsSection(
    tier: ProfileVisibility,
    title: String,
    description: String,
    uiState: ProfileEditUiState,
    onAction: (ProfileEditAction) -> Unit,
    editingRows: SnapshotStateMap<Pair<ProfileVisibility, String>, Boolean>,
) {
    val values = if (tier == ProfileVisibility.ANONYMOUS) uiState.anonymousValues else uiState.connectedValues
    val v: (ProfileField) -> String = { values[it].orEmpty() }
    fun vf(editTier: ProfileVisibility, field: ProfileField): String =
        (if (editTier == ProfileVisibility.ANONYMOUS) uiState.anonymousValues else uiState.connectedValues)[field].orEmpty()

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title, description)

        val nameDisplay = profileNameValue(values)
        if (nameDisplay != null || editingRows[tier to ProfileAttributeTypes.NAME] == true) {
            EditableFieldGroup(
                sectionTier = tier,
                type = ProfileAttributeTypes.NAME,
                icon = Icons.Outlined.Person,
                label = stringResource(MR.string.contactbook_detail_name),
                displayValue = nameDisplay,
                editingRows = editingRows,
                onAction = onAction,
            ) { editTier ->
                AttributeFields(ProfileAttributeTypes.NAME, { vf(editTier, it) }) { field, v ->
                    onAction(ProfileEditAction.FieldChanged(field, editTier, v))
                }
            }
        }

        val nicknameDisplay = v(ProfileField.NICKNAME).ifBlank { null }
        if (nicknameDisplay != null || editingRows[tier to ProfileAttributeTypes.NICKNAME] == true) {
            EditableFieldGroup(
                sectionTier = tier,
                type = ProfileAttributeTypes.NICKNAME,
                icon = Icons.Outlined.Badge,
                label = stringResource(MR.string.profile_edit_nickname),
                displayValue = nicknameDisplay,
                editingRows = editingRows,
                onAction = onAction,
            ) { editTier ->
                AttributeFields(ProfileAttributeTypes.NICKNAME, { vf(editTier, it) }) { field, v ->
                    onAction(ProfileEditAction.FieldChanged(field, editTier, v))
                }
            }
        }

        val statusDisplay = v(ProfileField.STATUS).ifBlank { null }
        if (statusDisplay != null || editingRows[tier to ProfileAttributeTypes.STATUS] == true) {
            EditableFieldGroup(
                sectionTier = tier,
                type = ProfileAttributeTypes.STATUS,
                icon = Icons.Outlined.Info,
                label = stringResource(MR.string.profile_edit_status),
                displayValue = statusDisplay,
                editingRows = editingRows,
                onAction = onAction,
            ) { editTier ->
                AttributeFields(ProfileAttributeTypes.STATUS, { vf(editTier, it) }) { field, v ->
                    onAction(ProfileEditAction.FieldChanged(field, editTier, v))
                }
            }
        }

        val birthdayDisplay = v(ProfileField.BIRTHDAY).ifBlank { null }
        if (birthdayDisplay != null || editingRows[tier to ProfileAttributeTypes.BIRTHDAY] == true) {
            EditableFieldGroup(
                sectionTier = tier,
                type = ProfileAttributeTypes.BIRTHDAY,
                icon = Icons.Outlined.Cake,
                label = stringResource(MR.string.profile_edit_birthday),
                displayValue = birthdayDisplay,
                editingRows = editingRows,
                onAction = onAction,
            ) { editTier ->
                AttributeFields(ProfileAttributeTypes.BIRTHDAY, { vf(editTier, it) }) { field, v ->
                    onAction(ProfileEditAction.FieldChanged(field, editTier, v))
                }
            }
        }

        val emailDisplay = v(ProfileField.EMAIL).ifBlank { null }
        if (emailDisplay != null || editingRows[tier to ProfileAttributeTypes.EMAIL] == true) {
            EditableFieldGroup(
                sectionTier = tier,
                type = ProfileAttributeTypes.EMAIL,
                icon = Icons.Outlined.Email,
                label = stringResource(MR.string.profile_edit_email),
                displayValue = emailDisplay,
                editingRows = editingRows,
                onAction = onAction,
                isValidForSave = { et -> isAttributeValid(ProfileAttributeTypes.EMAIL) { vf(et, it) } },
            ) { editTier ->
                AttributeFields(ProfileAttributeTypes.EMAIL, { vf(editTier, it) }) { field, v ->
                    onAction(ProfileEditAction.FieldChanged(field, editTier, v))
                }
            }
        }

        val phoneRaw = v(ProfileField.PHONE)
        val phoneDisplay = phoneRaw.ifBlank { null }?.let { formatPhoneForDisplay(it) }
        if (phoneDisplay != null || editingRows[tier to ProfileAttributeTypes.PHONE] == true) {
            EditableFieldGroup(
                sectionTier = tier,
                type = ProfileAttributeTypes.PHONE,
                icon = Icons.Outlined.Call,
                label = stringResource(MR.string.profile_edit_phone),
                displayValue = phoneDisplay,
                editingRows = editingRows,
                onAction = onAction,
                isValidForSave = { et -> isAttributeValid(ProfileAttributeTypes.PHONE) { vf(et, it) } },
            ) { editTier ->
                AttributeFields(ProfileAttributeTypes.PHONE, { vf(editTier, it) }) { field, v ->
                    onAction(ProfileEditAction.FieldChanged(field, editTier, v))
                }
            }
        }

        val addressDisplay = profileAddressValue(values)
        if (addressDisplay != null || editingRows[tier to ProfileAttributeTypes.ADDRESS] == true) {
            EditableFieldGroup(
                sectionTier = tier,
                type = ProfileAttributeTypes.ADDRESS,
                icon = Icons.Outlined.LocationOn,
                label = stringResource(MR.string.contactbook_detail_location),
                displayValue = addressDisplay,
                editingRows = editingRows,
                onAction = onAction,
            ) { editTier ->
                AttributeFields(ProfileAttributeTypes.ADDRESS, { vf(editTier, it) }) { field, v ->
                    onAction(ProfileEditAction.FieldChanged(field, editTier, v))
                }
            }
        }

        val twitterDisplay = v(ProfileField.TWITTER).ifBlank { null }
        if (twitterDisplay != null || editingRows[tier to ProfileAttributeTypes.TWITTER] == true) {
            EditableFieldGroup(
                sectionTier = tier,
                type = ProfileAttributeTypes.TWITTER,
                icon = Icons.Outlined.AlternateEmail,
                label = stringResource(MR.string.profile_edit_twitter),
                displayValue = twitterDisplay,
                editingRows = editingRows,
                onAction = onAction,
            ) { editTier ->
                AttributeFields(ProfileAttributeTypes.TWITTER, { vf(editTier, it) }) { field, v ->
                    onAction(ProfileEditAction.FieldChanged(field, editTier, v))
                }
            }
        }
        val facebookDisplay = v(ProfileField.FACEBOOK).ifBlank { null }
        if (facebookDisplay != null || editingRows[tier to ProfileAttributeTypes.FACEBOOK] == true) {
            EditableFieldGroup(
                sectionTier = tier,
                type = ProfileAttributeTypes.FACEBOOK,
                icon = Icons.Outlined.AlternateEmail,
                label = stringResource(MR.string.profile_edit_facebook),
                displayValue = facebookDisplay,
                editingRows = editingRows,
                onAction = onAction,
            ) { editTier ->
                AttributeFields(ProfileAttributeTypes.FACEBOOK, { vf(editTier, it) }) { field, v ->
                    onAction(ProfileEditAction.FieldChanged(field, editTier, v))
                }
            }
        }
        val instagramDisplay = v(ProfileField.INSTAGRAM).ifBlank { null }
        if (instagramDisplay != null || editingRows[tier to ProfileAttributeTypes.INSTAGRAM] == true) {
            EditableFieldGroup(
                sectionTier = tier,
                type = ProfileAttributeTypes.INSTAGRAM,
                icon = Icons.Outlined.AlternateEmail,
                label = stringResource(MR.string.profile_edit_instagram),
                displayValue = instagramDisplay,
                editingRows = editingRows,
                onAction = onAction,
            ) { editTier ->
                AttributeFields(ProfileAttributeTypes.INSTAGRAM, { vf(editTier, it) }) { field, v ->
                    onAction(ProfileEditAction.FieldChanged(field, editTier, v))
                }
            }
        }
        val tiktokDisplay = v(ProfileField.TIKTOK).ifBlank { null }
        if (tiktokDisplay != null || editingRows[tier to ProfileAttributeTypes.TIKTOK] == true) {
            EditableFieldGroup(
                sectionTier = tier,
                type = ProfileAttributeTypes.TIKTOK,
                icon = Icons.Outlined.AlternateEmail,
                label = stringResource(MR.string.profile_edit_tiktok),
                displayValue = tiktokDisplay,
                editingRows = editingRows,
                onAction = onAction,
            ) { editTier ->
                AttributeFields(ProfileAttributeTypes.TIKTOK, { vf(editTier, it) }) { field, v ->
                    onAction(ProfileEditAction.FieldChanged(field, editTier, v))
                }
            }
        }
        val linkedinDisplay = v(ProfileField.LINKEDIN).ifBlank { null }
        if (linkedinDisplay != null || editingRows[tier to ProfileAttributeTypes.LINKEDIN] == true) {
            EditableFieldGroup(
                sectionTier = tier,
                type = ProfileAttributeTypes.LINKEDIN,
                icon = Icons.Outlined.AlternateEmail,
                label = stringResource(MR.string.profile_edit_linkedin),
                displayValue = linkedinDisplay,
                editingRows = editingRows,
                onAction = onAction,
            ) { editTier ->
                AttributeFields(ProfileAttributeTypes.LINKEDIN, { vf(editTier, it) }) { field, v ->
                    onAction(ProfileEditAction.FieldChanged(field, editTier, v))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, description: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One profile attribute rendered contact-detail style — icon, label, current value. Tapping the row
 * expands it in place with a Public|Vetted toggle (defaulting to [sectionTier], the section it's
 * listed under) and [content]'s field(s) for whichever tier is currently selected. The checkmark
 * dispatches [ProfileEditAction.SaveAttribute] for that (type, tier) and collapses immediately —
 * [content]'s fields already write straight through as they change, so the value shown is correct
 * the instant it collapses regardless of when the save actually lands; a failure surfaces as a
 * screen-level snackbar and the row can simply be reopened to retry.
 */
@Composable
private fun EditableFieldGroup(
    sectionTier: ProfileVisibility,
    type: String,
    icon: ImageVector,
    label: String,
    displayValue: String?,
    editingRows: SnapshotStateMap<Pair<ProfileVisibility, String>, Boolean>,
    onAction: (ProfileEditAction) -> Unit,
    isValidForSave: (ProfileVisibility) -> Boolean = { true },
    content: @Composable ColumnScope.(editTier: ProfileVisibility) -> Unit,
) {
    val key = sectionTier to type
    val editing = editingRows[key] == true
    var selectedTier by remember(editing) { mutableStateOf(sectionTier) }
    val notSet = stringResource(MR.string.profile_edit_field_not_set)

    Column(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            modifier = if (editing) {
                Modifier.fillMaxWidth()
            } else {
                Modifier.fillMaxWidth().clickable { editingRows[key] = true }
            },
            leadingContent = { Icon(icon, contentDescription = null) },
            overlineContent = { Text(label) },
            headlineContent = {
                Text(
                    text = displayValue?.ifBlank { null } ?: notSet,
                    color = if (displayValue.isNullOrBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            },
            trailingContent = if (editing) {
                {
                    TextButton(
                        enabled = isValidForSave(selectedTier),
                        onClick = {
                            onAction(ProfileEditAction.SaveAttribute(type, selectedTier))
                            editingRows[key] = false
                        },
                    ) {
                        Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(MR.string.save))
                    }
                }
            } else {
                null
            },
        )
        AnimatedVisibility(visible = editing) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content(selectedTier)
                TierToggle(selected = selectedTier, onSelect = { selectedTier = it })
                Text(
                    text = stringResource(
                        if (selectedTier == ProfileVisibility.ANONYMOUS) {
                            MR.string.profile_edit_public_hint
                        } else {
                            MR.string.profile_edit_connected_fallback_hint
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Picks which of an attribute's two independent tier records a row's [content] shows/edits. */
@Composable
private fun TierToggle(selected: ProfileVisibility, onSelect: (ProfileVisibility) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = selected == ProfileVisibility.ANONYMOUS,
            onClick = { onSelect(ProfileVisibility.ANONYMOUS) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            label = { Text(stringResource(MR.string.profile_edit_visibility_public)) },
        )
        SegmentedButton(
            selected = selected == ProfileVisibility.CONNECTED,
            onClick = { onSelect(ProfileVisibility.CONNECTED) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            label = { Text(stringResource(MR.string.profile_edit_visibility_connected)) },
        )
    }
}

@Composable
private fun ProfileField(
    value: String,
    label: String,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
    errorText: String? = null,
    modifier: Modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        isError = isError,
        supportingText = if (isError && errorText != null) { { Text(errorText) } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier,
    )
}

/** One attribute type the "add attribute" FAB can offer — icon/label only; the actual editable
 *  fields for each [type] live in [ProfileFieldsSection]'s per-attribute blocks. */
private data class AttributeSpec(val type: String, val icon: ImageVector, val labelRes: StringResource)

private val ATTRIBUTE_SPECS = listOf(
    AttributeSpec(ProfileAttributeTypes.NAME, Icons.Outlined.Person, MR.string.contactbook_detail_name),
    AttributeSpec(ProfileAttributeTypes.NICKNAME, Icons.Outlined.Badge, MR.string.profile_edit_nickname),
    AttributeSpec(ProfileAttributeTypes.STATUS, Icons.Outlined.Info, MR.string.profile_edit_status),
    AttributeSpec(ProfileAttributeTypes.BIRTHDAY, Icons.Outlined.Cake, MR.string.profile_edit_birthday),
    AttributeSpec(ProfileAttributeTypes.EMAIL, Icons.Outlined.Email, MR.string.profile_edit_email),
    AttributeSpec(ProfileAttributeTypes.PHONE, Icons.Outlined.Call, MR.string.profile_edit_phone),
    AttributeSpec(ProfileAttributeTypes.ADDRESS, Icons.Outlined.LocationOn, MR.string.contactbook_detail_location),
    AttributeSpec(ProfileAttributeTypes.TWITTER, Icons.Outlined.AlternateEmail, MR.string.profile_edit_twitter),
    AttributeSpec(ProfileAttributeTypes.FACEBOOK, Icons.Outlined.AlternateEmail, MR.string.profile_edit_facebook),
    AttributeSpec(ProfileAttributeTypes.INSTAGRAM, Icons.Outlined.AlternateEmail, MR.string.profile_edit_instagram),
    AttributeSpec(ProfileAttributeTypes.TIKTOK, Icons.Outlined.AlternateEmail, MR.string.profile_edit_tiktok),
    AttributeSpec(ProfileAttributeTypes.LINKEDIN, Icons.Outlined.AlternateEmail, MR.string.profile_edit_linkedin),
)

/** Whether [type] has a value in [values] — the same blank check each [ProfileFieldsSection] block
 *  uses to decide whether to render, kept as one pure function so the FAB's "missing" list can
 *  never drift from what's actually hidden. */
private fun displayValueFor(type: String, values: Map<ProfileField, String>): String? = when (type) {
    ProfileAttributeTypes.NAME -> profileNameValue(values)
    ProfileAttributeTypes.NICKNAME -> values[ProfileField.NICKNAME]?.ifBlank { null }
    ProfileAttributeTypes.STATUS -> values[ProfileField.STATUS]?.ifBlank { null }
    ProfileAttributeTypes.BIRTHDAY -> values[ProfileField.BIRTHDAY]?.ifBlank { null }
    ProfileAttributeTypes.EMAIL -> values[ProfileField.EMAIL]?.ifBlank { null }
    ProfileAttributeTypes.PHONE -> values[ProfileField.PHONE]?.ifBlank { null }
    ProfileAttributeTypes.ADDRESS -> profileAddressValue(values)
    ProfileAttributeTypes.TWITTER -> values[ProfileField.TWITTER]?.ifBlank { null }
    ProfileAttributeTypes.FACEBOOK -> values[ProfileField.FACEBOOK]?.ifBlank { null }
    ProfileAttributeTypes.INSTAGRAM -> values[ProfileField.INSTAGRAM]?.ifBlank { null }
    ProfileAttributeTypes.TIKTOK -> values[ProfileField.TIKTOK]?.ifBlank { null }
    ProfileAttributeTypes.LINKEDIN -> values[ProfileField.LINKEDIN]?.ifBlank { null }
    else -> null
}

@Composable
private fun AddAttributeSheet(
    missingPublic: List<AttributeSpec>,
    missingVetted: List<AttributeSpec>,
    onPick: (AttributeSpec, ProfileVisibility) -> Unit,
    onDismiss: () -> Unit,
) {
    AdaptiveSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(MR.string.profile_edit_add_attribute_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            if (missingPublic.isNotEmpty()) {
                Text(
                    text = stringResource(MR.string.profile_edit_preview_section_public),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                missingPublic.forEach { spec ->
                    AddAttributeRow(spec) { onPick(spec, ProfileVisibility.ANONYMOUS) }
                }
            }
            if (missingVetted.isNotEmpty()) {
                Text(
                    text = stringResource(MR.string.profile_edit_preview_section_vetted),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                missingVetted.forEach { spec ->
                    AddAttributeRow(spec) { onPick(spec, ProfileVisibility.CONNECTED) }
                }
            }
        }
    }
}

@Composable
private fun AddAttributeRow(spec: AttributeSpec, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(spec.icon, contentDescription = null) },
        headlineContent = { Text(stringResource(spec.labelRes)) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

/**
 * Captures a brand-new attribute's value entirely as a local draft — nothing is written to
 * [ProfileEditUiState] (and so nothing appears in either section) until [onSave] fires, unlike an
 * existing row's inline editor which writes through [ProfileEditAction.FieldChanged] as you type.
 */
@Composable
private fun AddAttributeDialog(
    spec: AttributeSpec,
    initialTier: ProfileVisibility,
    onSave: (ProfileVisibility, Map<ProfileField, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var tier by remember { mutableStateOf(initialTier) }
    val draft = remember { mutableStateMapOf<ProfileField, String>() }
    val value: (ProfileField) -> String = { draft[it].orEmpty() }
    val onChange: (ProfileField, String) -> Unit = { field, v -> draft[field] = v }
    val valid = isAttributeValid(spec.type, value)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(spec.labelRes)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AttributeFields(spec.type, value, onChange)
                TierToggle(selected = tier, onSelect = { tier = it })
                Text(
                    text = stringResource(
                        if (tier == ProfileVisibility.ANONYMOUS) {
                            MR.string.profile_edit_public_hint
                        } else {
                            MR.string.profile_edit_connected_fallback_hint
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(tier, draft) }) {
                Text(stringResource(MR.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.string.cancel))
            }
        },
    )
}

/** Whether [type]'s current draft is well-formed enough to save — only Email/Phone constrain
 *  format; every other attribute type accepts anything (including blank, which just no-ops). */
private fun isAttributeValid(type: String, value: (ProfileField) -> String): Boolean = when (type) {
    ProfileAttributeTypes.EMAIL -> ContactFieldValidation.isValidEmail(value(ProfileField.EMAIL))
    ProfileAttributeTypes.PHONE -> ContactFieldValidation.isValidPhone(value(ProfileField.PHONE))
    else -> true
}

/**
 * The editable field(s) for one attribute type, bound generically via [value]/[onChange] so both an
 * existing row's inline editor (live [ProfileEditUiState]) and [AddAttributeDialog] (local draft)
 * can share the exact same field UI.
 */
@Composable
private fun AttributeFields(
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
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.NICKNAME, it) }
        }

        ProfileAttributeTypes.STATUS -> {
            ProfileField(
                value(ProfileField.STATUS),
                stringResource(MR.string.profile_edit_status),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.STATUS, it) }
        }

        ProfileAttributeTypes.BIRTHDAY -> {
            ProfileField(
                value = value(ProfileField.BIRTHDAY),
                label = stringResource(MR.string.profile_edit_birthday),
                placeholder = stringResource(MR.string.profile_edit_birthday_hint),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.BIRTHDAY, it) }
        }

        ProfileAttributeTypes.EMAIL -> {
            val emailValue = value(ProfileField.EMAIL)
            ProfileField(
                value = emailValue,
                label = stringResource(MR.string.profile_edit_email),
                keyboardType = KeyboardType.Email,
                isError = emailValue.isNotBlank() && !ContactFieldValidation.isValidEmail(emailValue),
                errorText = stringResource(MR.string.contactbook_error_email),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.EMAIL, it) }
            ProfileField(
                value = value(ProfileField.EMAIL_LABEL),
                label = stringResource(MR.string.profile_edit_email_label),
                placeholder = stringResource(MR.string.profile_edit_email_label_hint),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.EMAIL_LABEL, it) }
        }

        ProfileAttributeTypes.PHONE -> {
            val phoneValue = value(ProfileField.PHONE)
            PhoneNumberField(
                e164Value = phoneValue,
                onValueChange = { onChange(ProfileField.PHONE, it) },
                label = stringResource(MR.string.profile_edit_phone),
                isError = phoneValue.isNotBlank() && !ContactFieldValidation.isValidPhone(phoneValue),
                errorText = stringResource(MR.string.contactbook_error_phone),
                modifier = Modifier.fillMaxWidth(),
            )
            ProfileField(
                value = value(ProfileField.PHONE_LABEL),
                label = stringResource(MR.string.profile_edit_phone_label),
                placeholder = stringResource(MR.string.profile_edit_phone_label_hint),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.PHONE_LABEL, it) }
        }

        ProfileAttributeTypes.ADDRESS -> {
            ProfileField(
                value = value(ProfileField.ADDRESS_LABEL),
                label = stringResource(MR.string.profile_edit_address_label),
                placeholder = stringResource(MR.string.profile_edit_address_label_hint),
                modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.TWITTER, it) }
        }
        ProfileAttributeTypes.FACEBOOK -> {
            ProfileField(
                value(ProfileField.FACEBOOK),
                stringResource(MR.string.profile_edit_facebook),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.FACEBOOK, it) }
        }
        ProfileAttributeTypes.INSTAGRAM -> {
            ProfileField(
                value(ProfileField.INSTAGRAM),
                stringResource(MR.string.profile_edit_instagram),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.INSTAGRAM, it) }
        }
        ProfileAttributeTypes.TIKTOK -> {
            ProfileField(
                value(ProfileField.TIKTOK),
                stringResource(MR.string.profile_edit_tiktok),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.TIKTOK, it) }
        }
        ProfileAttributeTypes.LINKEDIN -> {
            ProfileField(
                value(ProfileField.LINKEDIN),
                stringResource(MR.string.profile_edit_linkedin),
                modifier = Modifier.fillMaxWidth(),
            ) { onChange(ProfileField.LINKEDIN, it) }
        }
    }
}
