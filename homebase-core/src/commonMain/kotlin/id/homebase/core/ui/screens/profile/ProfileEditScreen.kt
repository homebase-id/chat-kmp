@file:OptIn(ExperimentalMaterial3Api::class)

package id.homebase.core.ui.screens.profile

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.ui.screens.contactbook.components.PhoneNumberField
import id.homebase.resources.MR
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
import id.homebase.resources.profile_edit_retry
import id.homebase.resources.profile_edit_save
import id.homebase.resources.profile_edit_section_address
import id.homebase.resources.profile_edit_section_contact
import id.homebase.resources.profile_edit_section_name
import id.homebase.resources.profile_edit_section_social
import id.homebase.resources.profile_edit_status
import id.homebase.resources.profile_edit_surname
import id.homebase.resources.profile_edit_tiktok
import id.homebase.resources.profile_edit_title
import id.homebase.resources.profile_edit_twitter
import org.jetbrains.compose.resources.stringResource

@Composable
fun ProfileEditScreen(
    viewModel: ProfileEditViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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

@Composable
private fun ProfileForm(
    uiState: ProfileEditUiState,
    onAction: (ProfileEditAction) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        SectionHeader(stringResource(MR.string.profile_edit_section_name))
        ProfileField(uiState.givenName, stringResource(MR.string.profile_edit_given_name)) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.GIVEN_NAME, it))
        }
        ProfileField(uiState.surname, stringResource(MR.string.profile_edit_surname)) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.SURNAME, it))
        }
        ProfileField(uiState.additionalName, stringResource(MR.string.profile_edit_additional_name)) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.ADDITIONAL_NAME, it))
        }
        ProfileField(uiState.nickName, stringResource(MR.string.profile_edit_nickname)) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.NICKNAME, it))
        }
        ProfileField(uiState.status, stringResource(MR.string.profile_edit_status)) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.STATUS, it))
        }
        ProfileField(
            value = uiState.birthday,
            label = stringResource(MR.string.profile_edit_birthday),
            placeholder = stringResource(MR.string.profile_edit_birthday_hint),
        ) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.BIRTHDAY, it))
        }

        SectionHeader(stringResource(MR.string.profile_edit_section_contact))
        FieldGroup {
            ProfileField(
                value = uiState.email,
                label = stringResource(MR.string.profile_edit_email),
                keyboardType = KeyboardType.Email,
                isError = uiState.email.isNotBlank() && !uiState.emailValid,
                errorText = stringResource(MR.string.contactbook_error_email),
                modifier = Modifier.fillMaxWidth(),
            ) {
                onAction(ProfileEditAction.FieldChanged(ProfileField.EMAIL, it))
            }
            ProfileField(
                value = uiState.emailLabel,
                label = stringResource(MR.string.profile_edit_email_label),
                placeholder = stringResource(MR.string.profile_edit_email_label_hint),
                modifier = Modifier.fillMaxWidth(),
            ) {
                onAction(ProfileEditAction.FieldChanged(ProfileField.EMAIL_LABEL, it))
            }
        }
        FieldGroup {
            PhoneNumberField(
                e164Value = uiState.phone,
                onValueChange = { onAction(ProfileEditAction.FieldChanged(ProfileField.PHONE, it)) },
                label = stringResource(MR.string.profile_edit_phone),
                isError = uiState.phone.isNotBlank() && !uiState.phoneValid,
                errorText = stringResource(MR.string.contactbook_error_phone),
                modifier = Modifier.fillMaxWidth(),
            )
            ProfileField(
                value = uiState.phoneLabel,
                label = stringResource(MR.string.profile_edit_phone_label),
                placeholder = stringResource(MR.string.profile_edit_phone_label_hint),
                modifier = Modifier.fillMaxWidth(),
            ) {
                onAction(ProfileEditAction.FieldChanged(ProfileField.PHONE_LABEL, it))
            }
        }

        SectionHeader(stringResource(MR.string.profile_edit_section_address))
        FieldGroup {
            ProfileField(
                value = uiState.addressLabel,
                label = stringResource(MR.string.profile_edit_address_label),
                placeholder = stringResource(MR.string.profile_edit_address_label_hint),
                modifier = Modifier.fillMaxWidth(),
            ) {
                onAction(ProfileEditAction.FieldChanged(ProfileField.ADDRESS_LABEL, it))
            }
            ProfileField(
                value = uiState.address1,
                label = stringResource(MR.string.profile_edit_address1),
                modifier = Modifier.fillMaxWidth(),
            ) {
                onAction(ProfileEditAction.FieldChanged(ProfileField.ADDRESS1, it))
            }
            ProfileField(
                value = uiState.address2,
                label = stringResource(MR.string.profile_edit_address2),
                modifier = Modifier.fillMaxWidth(),
            ) {
                onAction(ProfileEditAction.FieldChanged(ProfileField.ADDRESS2, it))
            }
            ProfileField(
                value = uiState.postcode,
                label = stringResource(MR.string.profile_edit_postcode),
                modifier = Modifier.fillMaxWidth(),
            ) {
                onAction(ProfileEditAction.FieldChanged(ProfileField.POSTCODE, it))
            }
            ProfileField(
                value = uiState.city,
                label = stringResource(MR.string.profile_edit_city),
                modifier = Modifier.fillMaxWidth(),
            ) {
                onAction(ProfileEditAction.FieldChanged(ProfileField.CITY, it))
            }
            ProfileField(
                value = uiState.country,
                label = stringResource(MR.string.profile_edit_country),
                modifier = Modifier.fillMaxWidth(),
            ) {
                onAction(ProfileEditAction.FieldChanged(ProfileField.COUNTRY, it))
            }
        }

        SectionHeader(stringResource(MR.string.profile_edit_section_social))
        ProfileField(uiState.twitter, stringResource(MR.string.profile_edit_twitter)) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.TWITTER, it))
        }
        ProfileField(uiState.facebook, stringResource(MR.string.profile_edit_facebook)) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.FACEBOOK, it))
        }
        ProfileField(uiState.instagram, stringResource(MR.string.profile_edit_instagram)) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.INSTAGRAM, it))
        }
        ProfileField(uiState.tiktok, stringResource(MR.string.profile_edit_tiktok)) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.TIKTOK, it))
        }
        ProfileField(uiState.linkedin, stringResource(MR.string.profile_edit_linkedin)) {
            onAction(ProfileEditAction.FieldChanged(ProfileField.LINKEDIN, it))
        }

        Spacer(Modifier.height(24.dp))
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

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
    )
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
 * Groups related fields (e.g. email + its "Personal"/"Work" label, or an address's parts) in a
 * bordered card so the set reads as one unit instead of unrelated fields.
 */
@Composable
private fun FieldGroup(content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}
