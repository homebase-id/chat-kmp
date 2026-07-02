@file:OptIn(ExperimentalMaterial3Api::class)

package id.homebase.core.ui.screens.profile

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.core.ui.screens.contactbook.components.PhoneNumberField
import id.homebase.resources.MR
import id.homebase.resources.contactbook_detail_tab_about
import id.homebase.resources.contactbook_detail_tab_details
import id.homebase.resources.contactbook_error_email
import id.homebase.resources.contactbook_error_phone
import id.homebase.resources.menu_back
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
import id.homebase.resources.profile_edit_public_hint
import id.homebase.resources.profile_edit_retry
import id.homebase.resources.profile_edit_save
import id.homebase.resources.profile_edit_status
import id.homebase.resources.profile_edit_surname
import id.homebase.resources.profile_edit_tiktok
import id.homebase.resources.profile_edit_title
import id.homebase.resources.profile_edit_twitter
import id.homebase.resources.profile_edit_visibility_connected
import id.homebase.resources.profile_edit_visibility_public
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
    var previewTier by remember { mutableStateOf(ProfileVisibility.ANONYMOUS) }

    val errForbidden = stringResource(MR.string.profile_edit_error_forbidden)
    val errSave = stringResource(MR.string.profile_edit_error_save)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ProfileEditEvent.Saved, ProfileEditEvent.Back -> onBack()
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
                tier = previewTier,
                onTierChange = { previewTier = it },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            else -> ProfileForm(
                uiState = uiState,
                onAction = viewModel::onAction,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
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

/** Mirrors [id.homebase.core.ui.screens.contactbook.detail.ContactDetailScreen]'s Details/About
 *  tabs, so the owner's own profile reads the same way any other identity's profile does. */
private enum class ProfileFormTab(val labelRes: StringResource) {
    DETAILS(MR.string.contactbook_detail_tab_details),
    ABOUT(MR.string.contactbook_detail_tab_about),
}

@Composable
private fun ProfileForm(
    uiState: ProfileEditUiState,
    onAction: (ProfileEditAction) -> Unit,
    modifier: Modifier,
) {
    var selectedTab by remember { mutableStateOf(ProfileFormTab.DETAILS) }

    Column(modifier = modifier) {
        TabRow(selectedTabIndex = selectedTab.ordinal) {
            ProfileFormTab.entries.forEach { tab ->
                Tab(
                    selected = tab == selectedTab,
                    onClick = { selectedTab = tab },
                    text = { Text(stringResource(tab.labelRes)) },
                )
            }
        }

        // Fresh scroll position per tab.
        val scroll = remember(selectedTab) { ScrollState(0) }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            when (selectedTab) {
                ProfileFormTab.DETAILS -> DetailsFields(uiState, onAction)
                ProfileFormTab.ABOUT -> AboutFields(uiState, onAction)
            }
            Spacer(Modifier.height(16.dp))
        }

        // Save applies to edits from both tabs together, so it stays outside the tabbed/scrolling
        // area rather than being duplicated per tab or scrolling away.
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Button(
                onClick = { onAction(ProfileEditAction.SaveClicked) },
                enabled = uiState.canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(MR.string.profile_edit_save))
                }
            }
        }
    }
}

@Composable
private fun DetailsFields(uiState: ProfileEditUiState, onAction: (ProfileEditAction) -> Unit) {
    FieldGroup(ProfileAttributeTypes.NAME, uiState) { tier ->
        val v: (ProfileField) -> String = { uiState.value(it, tier) }
        ProfileField(
            v(ProfileField.GIVEN_NAME),
            stringResource(MR.string.profile_edit_given_name),
            modifier = Modifier.fillMaxWidth(),
        ) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.GIVEN_NAME, tier, it))
        }
        ProfileField(
            v(ProfileField.SURNAME),
            stringResource(MR.string.profile_edit_surname),
            modifier = Modifier.fillMaxWidth(),
        ) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.SURNAME, tier, it))
        }
        ProfileField(
            v(ProfileField.ADDITIONAL_NAME),
            stringResource(MR.string.profile_edit_additional_name),
            modifier = Modifier.fillMaxWidth(),
        ) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.ADDITIONAL_NAME, tier, it))
        }
    }
    FieldGroup(ProfileAttributeTypes.NICKNAME, uiState) { tier ->
        ProfileField(
            uiState.value(ProfileField.NICKNAME, tier),
            stringResource(MR.string.profile_edit_nickname),
            modifier = Modifier.fillMaxWidth(),
        ) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.NICKNAME, tier, it))
        }
    }
    FieldGroup(ProfileAttributeTypes.PHONE, uiState) { tier ->
        val v: (ProfileField) -> String = { uiState.value(it, tier) }
        val phoneValue = v(ProfileField.PHONE)
        PhoneNumberField(
            e164Value = phoneValue,
            onValueChange = { onAction(ProfileEditAction.FieldChanged(ProfileField.PHONE, tier, it)) },
            label = stringResource(MR.string.profile_edit_phone),
            isError = phoneValue.isNotBlank() && !uiState.phoneValid,
            errorText = stringResource(MR.string.contactbook_error_phone),
            modifier = Modifier.fillMaxWidth(),
        )
        ProfileField(
            value = v(ProfileField.PHONE_LABEL),
            label = stringResource(MR.string.profile_edit_phone_label),
            placeholder = stringResource(MR.string.profile_edit_phone_label_hint),
            modifier = Modifier.fillMaxWidth(),
        ) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.PHONE_LABEL, tier, it))
        }
    }
    FieldGroup(ProfileAttributeTypes.EMAIL, uiState) { tier ->
        val v: (ProfileField) -> String = { uiState.value(it, tier) }
        val emailValue = v(ProfileField.EMAIL)
        ProfileField(
            value = emailValue,
            label = stringResource(MR.string.profile_edit_email),
            keyboardType = KeyboardType.Email,
            isError = emailValue.isNotBlank() && !uiState.emailValid,
            errorText = stringResource(MR.string.contactbook_error_email),
            modifier = Modifier.fillMaxWidth(),
        ) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.EMAIL, tier, it))
        }
        ProfileField(
            value = v(ProfileField.EMAIL_LABEL),
            label = stringResource(MR.string.profile_edit_email_label),
            placeholder = stringResource(MR.string.profile_edit_email_label_hint),
            modifier = Modifier.fillMaxWidth(),
        ) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.EMAIL_LABEL, tier, it))
        }
    }
    FieldGroup(ProfileAttributeTypes.ADDRESS, uiState) { tier ->
        val v: (ProfileField) -> String = { uiState.value(it, tier) }
        ProfileField(
            value = v(ProfileField.ADDRESS_LABEL),
            label = stringResource(MR.string.profile_edit_address_label),
            placeholder = stringResource(MR.string.profile_edit_address_label_hint),
            modifier = Modifier.fillMaxWidth(),
        ) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.ADDRESS_LABEL, tier, it))
        }
        ProfileField(
            value = v(ProfileField.ADDRESS1),
            label = stringResource(MR.string.profile_edit_address1),
            modifier = Modifier.fillMaxWidth(),
        ) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.ADDRESS1, tier, it))
        }
        ProfileField(
            value = v(ProfileField.ADDRESS2),
            label = stringResource(MR.string.profile_edit_address2),
            modifier = Modifier.fillMaxWidth(),
        ) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.ADDRESS2, tier, it))
        }
        ProfileField(
            value = v(ProfileField.POSTCODE),
            label = stringResource(MR.string.profile_edit_postcode),
            modifier = Modifier.fillMaxWidth(),
        ) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.POSTCODE, tier, it))
        }
        ProfileField(
            value = v(ProfileField.CITY),
            label = stringResource(MR.string.profile_edit_city),
            modifier = Modifier.fillMaxWidth(),
        ) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.CITY, tier, it))
        }
        ProfileField(
            value = v(ProfileField.COUNTRY),
            label = stringResource(MR.string.profile_edit_country),
            modifier = Modifier.fillMaxWidth(),
        ) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.COUNTRY, tier, it))
        }
    }
    FieldGroup(ProfileAttributeTypes.BIRTHDAY, uiState) { tier ->
        ProfileField(
            value = uiState.value(ProfileField.BIRTHDAY, tier),
            label = stringResource(MR.string.profile_edit_birthday),
            placeholder = stringResource(MR.string.profile_edit_birthday_hint),
            modifier = Modifier.fillMaxWidth(),
        ) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.BIRTHDAY, tier, it))
        }
    }
}

@Composable
private fun AboutFields(uiState: ProfileEditUiState, onAction: (ProfileEditAction) -> Unit) {
    FieldGroup(ProfileAttributeTypes.STATUS, uiState) { tier ->
        ProfileField(
            uiState.value(ProfileField.STATUS, tier),
            stringResource(MR.string.profile_edit_status),
            modifier = Modifier.fillMaxWidth(),
        ) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.STATUS, tier, it))
        }
    }
    FieldGroup(ProfileAttributeTypes.TWITTER, uiState) { tier ->
        ProfileField(
            uiState.value(ProfileField.TWITTER, tier),
            stringResource(MR.string.profile_edit_twitter),
            modifier = Modifier.fillMaxWidth(),
        ) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.TWITTER, tier, it))
        }
    }
    FieldGroup(ProfileAttributeTypes.FACEBOOK, uiState) { tier ->
        ProfileField(
            uiState.value(ProfileField.FACEBOOK, tier),
            stringResource(MR.string.profile_edit_facebook),
            modifier = Modifier.fillMaxWidth(),
        ) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.FACEBOOK, tier, it))
        }
    }
    FieldGroup(ProfileAttributeTypes.INSTAGRAM, uiState) { tier ->
        ProfileField(
            uiState.value(ProfileField.INSTAGRAM, tier),
            stringResource(MR.string.profile_edit_instagram),
            modifier = Modifier.fillMaxWidth(),
        ) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.INSTAGRAM, tier, it))
        }
    }
    FieldGroup(ProfileAttributeTypes.TIKTOK, uiState) { tier ->
        ProfileField(
            uiState.value(ProfileField.TIKTOK, tier),
            stringResource(MR.string.profile_edit_tiktok),
            modifier = Modifier.fillMaxWidth(),
        ) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.TIKTOK, tier, it))
        }
    }
    FieldGroup(ProfileAttributeTypes.LINKEDIN, uiState) { tier ->
        ProfileField(
            uiState.value(ProfileField.LINKEDIN, tier),
            stringResource(MR.string.profile_edit_linkedin),
            modifier = Modifier.fillMaxWidth(),
        ) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.LINKEDIN, tier, it))
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

/**
 * Groups a profile attribute's fields (e.g. email + its "Personal"/"Work" label, or an address's
 * parts) in a bordered card so the set reads as one unit. Each attribute is backed by up to two
 * independent records — Anonymous and Connected — so this owns which tier's values [content] is
 * currently showing/editing via a tab control; switching tabs is a pure display choice, not
 * something that itself needs saving.
 */
@Composable
private fun FieldGroup(
    type: String,
    uiState: ProfileEditUiState,
    content: @Composable ColumnScope.(tier: ProfileVisibility) -> Unit,
) {
    var activeTier by remember(type) { mutableStateOf(ProfileVisibility.ANONYMOUS) }
    val hasConnectedContent = remember(uiState.connectedValues, type) {
        ProfileEditViewModel.TYPE_FIELDS[type].orEmpty()
            .any { (field, _) -> uiState.connectedValues[field]?.isNotBlank() == true }
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TierTabs(
                selected = activeTier,
                hasConnectedContent = hasConnectedContent,
                onSelect = { activeTier = it },
                modifier = Modifier.align(Alignment.End),
            )
            Text(
                text = stringResource(
                    if (activeTier == ProfileVisibility.ANONYMOUS) MR.string.profile_edit_public_hint
                    else MR.string.profile_edit_connected_fallback_hint
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content(activeTier)
        }
    }
}

/** Which tier of a [FieldGroup]'s two independent records is currently shown/edited. A small dot
 *  marks the Connected tab when it already holds a value, so switching isn't required to know. */
@Composable
private fun TierTabs(
    selected: ProfileVisibility,
    hasConnectedContent: Boolean,
    onSelect: (ProfileVisibility) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
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
            label = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(stringResource(MR.string.profile_edit_visibility_connected))
                    if (hasConnectedContent) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            },
        )
    }
}
