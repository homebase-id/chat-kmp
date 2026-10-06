@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContactPage
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
import id.homebase.resources.profile_edit_preview_section_vetted
import id.homebase.resources.profile_edit_preview_section_vetted_desc
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
import id.homebase.core.widget.AdaptiveSheet
import id.homebase.core.ui.screens.contactbook.components.PhoneNumberField
import id.homebase.core.ui.screens.contactbook.components.formatPhoneForDisplay
import id.homebase.core.widget.SettingsTopBar
import id.homebase.core.ui.screens.card.CardCircle
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.contactbook_detail_location
import id.homebase.resources.contactbook_detail_name
import id.homebase.resources.contactbook_error_birthday
import id.homebase.resources.contactbook_error_email
import id.homebase.resources.contactbook_error_phone
import id.homebase.resources.profile_card_open
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
import id.homebase.resources.profile_edit_photos_title
import id.homebase.resources.profile_edit_photos_desc
import id.homebase.resources.profile_edit_details_title
import id.homebase.resources.profile_edit_details_desc
import id.homebase.resources.profile_edit_phone
import id.homebase.resources.profile_edit_phone_label
import id.homebase.resources.profile_edit_phone_label_hint
import id.homebase.resources.profile_edit_postcode
import id.homebase.resources.profile_edit_preview_enter
import id.homebase.resources.profile_edit_preview_exit
import id.homebase.resources.profile_edit_preview_section_public
import id.homebase.resources.profile_edit_preview_section_public_desc
import id.homebase.resources.profile_edit_preview_section_circles
import id.homebase.resources.profile_edit_preview_section_circles_desc
import id.homebase.resources.profile_edit_retry
import id.homebase.resources.profile_edit_status
import id.homebase.resources.profile_edit_surname
import id.homebase.resources.profile_edit_tiktok
import id.homebase.resources.profile_edit_title
import id.homebase.resources.profile_edit_twitter
import id.homebase.resources.profile_avatar_edit_error_delete
import id.homebase.resources.profile_avatar_edit_error_too_large
import id.homebase.resources.profile_avatar_edit_error_upload
import id.homebase.resources.profile_edit_audience_only_me
import id.homebase.resources.profile_edit_visibility_public
import id.homebase.resources.save
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Composable
fun ProfileEditScreen(
    viewModel: ProfileEditViewModel,
    avatarViewModel: ProfileAvatarEditViewModel,
    onBack: () -> Unit,
    onNavigateToCropper: (Uuid) -> Unit,
    onOpenCard: (() -> Unit)?,
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val avatarUiState by avatarViewModel.state.collectAsStateWithLifecycle()
    val motion = MaterialTheme.motionScheme
    val snackbarHostState = remember { SnackbarHostState() }
    var previewMode by remember { mutableStateOf(false) }

    val errForbidden = stringResource(MR.string.profile_edit_error_forbidden)
    val errSave = stringResource(MR.string.profile_edit_error_save)
    val errAvatarUpload = stringResource(MR.string.profile_avatar_edit_error_upload)
    val errAvatarTooLarge = stringResource(MR.string.profile_avatar_edit_error_too_large)
    val errAvatarDelete = stringResource(MR.string.profile_avatar_edit_error_delete)

    val anonymousPhotoPicker = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
        file?.let { avatarViewModel.onAction(ProfileAvatarEditAction.PhotoPicked(ProfileVisibility.ANONYMOUS, it)) }
    }
    val onlyMePhotoPicker = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
        file?.let { avatarViewModel.onAction(ProfileAvatarEditAction.PhotoPicked(ProfileVisibility.OWNER, it)) }
    }

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

    LaunchedEffect(Unit) {
        avatarViewModel.events.collect { event ->
            when (event) {
                is ProfileAvatarEditEvent.NavigateToCropper -> onNavigateToCropper(event.requestId)
                ProfileAvatarEditEvent.Back -> Unit // this screen's own back arrow drives navigation, not the avatar VM's.
                is ProfileAvatarEditEvent.UploadFailed -> snackbarHostState.showSnackbar(errAvatarUpload)
                is ProfileAvatarEditEvent.UploadTooLarge -> snackbarHostState.showSnackbar(errAvatarTooLarge)
                is ProfileAvatarEditEvent.DeleteFailed -> snackbarHostState.showSnackbar(errAvatarDelete)
            }
        }
    }

    Scaffold(
        topBar = {
            SettingsTopBar(
                title = stringResource(MR.string.profile_edit_title),
                onBack = { viewModel.onAction(ProfileEditAction.BackClicked) },
                actions = {
                    if (!uiState.isLoading && !uiState.loadFailed) {
                        IconButton(onClick = { previewMode = !previewMode }) {
                            Crossfade(
                                targetState = previewMode,
                                animationSpec = motion.fastEffectsSpec(),
                            ) { preview ->
                                Icon(
                                    imageVector = if (preview) Icons.Outlined.Edit else Icons.Outlined.Visibility,
                                    contentDescription = stringResource(
                                        if (preview) MR.string.profile_edit_preview_exit
                                        else MR.string.profile_edit_preview_enter
                                    ),
                                )
                            }
                        }
                        if (onOpenCard != null) {
                            IconButton(onClick = onOpenCard) {
                                Icon(
                                    imageVector = Icons.Outlined.ContactPage,
                                    contentDescription = stringResource(MR.string.profile_card_open),
                                )
                            }
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
            else -> {
                AnimatedContent(
                    targetState = previewMode,
                    transitionSpec = {
                        fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())
                    },
                ) { preview ->
                    if (preview) {
                        ProfilePreview(
                            uiState = uiState,
                            modifier = Modifier.fillMaxSize().padding(padding),
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                            ProfileForm(
                                uiState = uiState,
                                onAction = viewModel::onAction,
                                avatarUiState = avatarUiState,
                                onAvatarAction = avatarViewModel::onAction,
                                onPickAnonymousPhoto = { anonymousPhotoPicker.launch() },
                                onPickOnlyMePhoto = { onlyMePhotoPicker.launch() },
                                modifier = Modifier.fillMaxSize(),
                            )
                            AnimatedVisibility(
                                visible = uiState.savingAttributes.isNotEmpty(),
                                modifier = Modifier.align(Alignment.TopCenter),
                                enter = fadeIn(motion.defaultEffectsSpec()),
                                exit = fadeOut(motion.defaultEffectsSpec()),
                            ) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
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
internal fun LoadFailedState(modifier: Modifier, onRetry: () -> Unit) {
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
 * Photos first, then every detail in one list. There's no screen-wide Save — tapping a row expands
 * it in place with its field(s) and the shared [AudiencePicker]; the checkmark persists just that
 * one attribute with the chosen audience.
 */
@Composable
private fun ProfileForm(
    uiState: ProfileEditUiState,
    onAction: (ProfileEditAction) -> Unit,
    avatarUiState: ProfileAvatarEditUiState,
    onAvatarAction: (ProfileAvatarEditAction) -> Unit,
    onPickAnonymousPhoto: () -> Unit,
    onPickOnlyMePhoto: () -> Unit,
    modifier: Modifier,
) {
    // Keeps a row mounted (and visible) for the whole edit session once it has a value. Blank
    // attributes are only added via the FAB's dialog, below.
    val editingRows = remember { mutableStateMapOf<String, Boolean>() }
    var showAddSheet by remember { mutableStateOf(false) }
    var addDialogTarget by remember { mutableStateOf<AttributeSpec?>(null) }

    val missingAttributes = ATTRIBUTE_SPECS.filter { displayValueFor(it.type, uiState.values) == null }
    val hasMissing = missingAttributes.isNotEmpty()
    val motion = MaterialTheme.motionScheme

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))
            SectionHeader(
                stringResource(MR.string.profile_edit_photos_title),
                stringResource(MR.string.profile_edit_photos_desc),
            )
            PhotoBlock(
                label = stringResource(MR.string.profile_edit_visibility_public),
                tier = ProfileVisibility.ANONYMOUS,
                photoState = if (avatarUiState.isLoading) null else avatarUiState.anonymous,
                onAvatarAction = onAvatarAction,
                onPickPhoto = onPickAnonymousPhoto,
            )
            PhotoBlock(
                label = stringResource(MR.string.profile_edit_audience_only_me),
                tier = ProfileVisibility.OWNER,
                photoState = if (avatarUiState.isLoading) null else avatarUiState.onlyMe,
                onAvatarAction = onAvatarAction,
                onPickPhoto = onPickOnlyMePhoto,
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp))

            Column(
                modifier = Modifier.fillMaxWidth().animateContentSize(motion.defaultSpatialSpec()),
            ) {
                SectionHeader(
                    stringResource(MR.string.profile_edit_details_title),
                    stringResource(MR.string.profile_edit_details_desc),
                )
                ATTRIBUTE_SPECS.forEach { spec ->
                    val display = displayValueFor(spec.type, uiState.values)
                        ?.let { if (spec.type == ProfileAttributeTypes.PHONE) formatPhoneForDisplay(it) else it }
                    if (display != null || editingRows[spec.type] == true) {
                        val audience = uiState.audience(spec.type)
                        EditableFieldGroup(
                            type = spec.type,
                            icon = spec.icon,
                            label = stringResource(spec.labelRes),
                            displayValue = display,
                            audience = audience,
                            circles = uiState.circles,
                            editingRows = editingRows,
                            onAction = onAction,
                            canSave = isAttributeValid(spec.type) { uiState.value(it) } && audience.isSavable,
                        ) {
                            AttributeFields(spec.type, { uiState.value(it) }) { field, v ->
                                onAction(ProfileEditAction.FieldChanged(field, v))
                            }
                        }
                    }
                }
            }
            // Clearance so the last row isn't hidden behind the floating action button.
            Spacer(Modifier.height(88.dp))
        }

        AnimatedVisibility(
            visible = hasMissing,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            enter = scaleIn(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec()),
            exit = scaleOut(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            FloatingActionButton(onClick = { showAddSheet = true }) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(MR.string.profile_edit_add_attribute),
                )
            }
        }
    }

    if (showAddSheet) {
        AddAttributeSheet(
            missing = missingAttributes,
            onPick = { spec ->
                showAddSheet = false
                addDialogTarget = spec
            },
            onDismiss = { showAddSheet = false },
        )
    }

    addDialogTarget?.let { spec ->
        AddAttributeDialog(
            spec = spec,
            circles = uiState.circles,
            onSave = { audience, values ->
                ProfileEditViewModel.TYPE_FIELDS[spec.type].orEmpty().forEach { (field, _) ->
                    onAction(ProfileEditAction.FieldChanged(field, values[field].orEmpty()))
                }
                onAction(ProfileEditAction.AudienceChanged(spec.type, audience))
                onAction(ProfileEditAction.SaveAttribute(spec.type))
                addDialogTarget = null
            },
            onDismiss = { addDialogTarget = null },
        )
    }
}

/** One photo slot. Tapping the photo reveals the camera badge and Remove button; Save collapses them again. */
@Composable
private fun PhotoBlock(
    label: String,
    tier: ProfileVisibility,
    photoState: PhotoTierUiState?,
    onAvatarAction: (ProfileAvatarEditAction) -> Unit,
    onPickPhoto: () -> Unit,
) {
    if (photoState == null) return
    var photoRevealed by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PhotoTierSection(
            tier = photoState,
            onPick = onPickPhoto,
            onRemove = { onAvatarAction(ProfileAvatarEditAction.RemoveClicked(tier)) },
            onSaveClicked = {
                onAvatarAction(ProfileAvatarEditAction.SaveClicked(tier))
                photoRevealed = false
            },
            controlsVisible = photoRevealed,
            centered = true,
            onPhotoTap = { photoRevealed = true },
        ) {
            ExistingAvatarContent(photoState.existing, "ProfileEditScreen")
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
 * One profile attribute rendered contact-detail style — icon, label, current value and the
 * audience it is shown to. Tapping the row expands it in place with [content]'s field(s) and the
 * shared [AudiencePicker]. The checkmark dispatches [ProfileEditAction.SaveAttribute] and collapses
 * immediately — the fields write straight through as they change, so the value shown is correct
 * the instant it collapses; a failure surfaces as a screen-level snackbar and the row can be
 * reopened to retry.
 */
@Composable
private fun EditableFieldGroup(
    type: String,
    icon: ImageVector,
    label: String,
    displayValue: String?,
    audience: ProfileAudience,
    circles: List<CardCircle>,
    editingRows: SnapshotStateMap<String, Boolean>,
    onAction: (ProfileEditAction) -> Unit,
    canSave: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val editing = editingRows[type] == true
    val saveVisibility = remember { MutableTransitionState(editing) }
    saveVisibility.targetState = editing
    val notSet = stringResource(MR.string.profile_edit_field_not_set)

    Column(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())
                .then(if (editing) Modifier else Modifier.clickable { editingRows[type] = true }),
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
            supportingContent = if (!editing && !displayValue.isNullOrBlank()) {
                { Text(audienceSummary(audience, circles)) }
            } else {
                null
            },
            trailingContent = if (saveVisibility.currentState || saveVisibility.targetState) {
                {
                    val motion = MaterialTheme.motionScheme
                    AnimatedVisibility(
                        visibleState = saveVisibility,
                        enter = fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec()),
                        exit = fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec()),
                    ) {
                        TextButton(
                            enabled = canSave,
                            onClick = {
                                onAction(ProfileEditAction.SaveAttribute(type))
                                editingRows[type] = false
                            },
                        ) {
                            Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(MR.string.save))
                        }
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
                content()
                AudiencePicker(
                    audience = audience,
                    circles = circles,
                    onChange = { onAction(ProfileEditAction.AudienceChanged(type, it)) },
                )
            }
        }
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
 *  fields for each [type] live in [ProfileForm]'s per-attribute rows. */
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

/** Whether [type] has a value in [values] — the same blank check each [ProfileForm] row
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
    missing: List<AttributeSpec>,
    onPick: (AttributeSpec) -> Unit,
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
            missing.forEach { spec ->
                AddAttributeRow(spec) { dismiss { onPick(spec) } }
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
    circles: List<CardCircle>,
    onSave: (ProfileAudience, Map<ProfileField, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var audience by remember { mutableStateOf<ProfileAudience>(ProfileAudience.Public) }
    val draft = remember { mutableStateMapOf<ProfileField, String>() }
    val value: (ProfileField) -> String = { draft[it].orEmpty() }
    val onChange: (ProfileField, String) -> Unit = { field, v -> draft[field] = v }
    val valid = isAttributeValid(spec.type, value) && audience.isSavable

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(spec.labelRes)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AttributeFields(spec.type, value, onChange)
                AudiencePicker(audience = audience, circles = circles, onChange = { audience = it })
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(audience, draft) }) {
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

/** Whether [type]'s current draft is well-formed enough to save — only Email/Phone/Birthday
 *  constrain format; every other attribute type accepts anything (including blank, which just
 *  no-ops). */
private fun isAttributeValid(type: String, value: (ProfileField) -> String): Boolean = when (type) {
    ProfileAttributeTypes.EMAIL -> ContactFieldValidation.isValidEmail(value(ProfileField.EMAIL))
    ProfileAttributeTypes.PHONE -> ContactFieldValidation.isValidPhone(value(ProfileField.PHONE))
    ProfileAttributeTypes.BIRTHDAY ->
        ContactFieldValidation.isValidBirthday(value(ProfileField.BIRTHDAY))
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
            val birthdayValue = value(ProfileField.BIRTHDAY)
            ProfileField(
                value = birthdayValue,
                label = stringResource(MR.string.profile_edit_birthday),
                placeholder = stringResource(MR.string.profile_edit_birthday_hint),
                isError = birthdayValue.isNotBlank() &&
                    !ContactFieldValidation.isValidBirthday(birthdayValue),
                errorText = stringResource(MR.string.contactbook_error_birthday),
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
