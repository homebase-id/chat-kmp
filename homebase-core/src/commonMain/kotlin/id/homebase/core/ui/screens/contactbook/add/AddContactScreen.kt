@file:OptIn(ExperimentalMaterial3Api::class)

package id.homebase.core.ui.screens.contactbook.add

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAddAlt1
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import id.homebase.api.client.identity.PublicIdentity
import id.homebase.api.client.identity.displayNameOrDomain
import id.homebase.api.client.identity.initials
import id.homebase.api.common.OdinId
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ContactAvatar
import id.homebase.core.connections.ConnectRequestAction
import id.homebase.core.connections.ConnectRequestBottomSheet
import id.homebase.core.connections.ConnectRequestViewModel
import id.homebase.core.connections.RecipientResolution
import id.homebase.core.ui.screens.contactbook.components.PhoneNumberField
import id.homebase.resources.MR
import id.homebase.resources.add_contact_already_connected
import id.homebase.resources.add_contact_already_saved
import id.homebase.resources.add_contact_blocked
import id.homebase.resources.add_contact_byid_link
import id.homebase.resources.add_contact_details_optional
import id.homebase.resources.add_contact_invalid
import id.homebase.resources.add_contact_lead_help
import id.homebase.resources.add_contact_manual_link
import id.homebase.resources.add_contact_not_found
import id.homebase.resources.add_contact_odinid_hint
import id.homebase.resources.add_contact_odinid_label
import id.homebase.resources.add_contact_photo_from_profile
import id.homebase.resources.add_contact_resolving
import id.homebase.resources.add_contact_save_as_new
import id.homebase.resources.add_contact_send_request
import id.homebase.resources.add_contact_send_request_help
import id.homebase.resources.add_contact_title
import id.homebase.resources.contactbook_action_request_accepted
import id.homebase.resources.contactbook_action_request_cancelled
import id.homebase.resources.contactbook_action_request_rejected
import id.homebase.resources.contactbook_detail_accept
import id.homebase.resources.contactbook_detail_cancel_request
import id.homebase.resources.contactbook_detail_request_incoming
import id.homebase.resources.contactbook_detail_request_outgoing
import id.homebase.resources.contactbook_detail_reject
import id.homebase.resources.auto_connect_failed_generic
import id.homebase.resources.contactbook_edit_change_photo
import id.homebase.resources.contactbook_edit_city
import id.homebase.resources.contactbook_edit_country
import id.homebase.resources.contactbook_edit_email
import id.homebase.resources.contactbook_edit_given_name
import id.homebase.resources.contactbook_edit_phone
import id.homebase.resources.contactbook_edit_save
import id.homebase.resources.contactbook_edit_surname
import id.homebase.resources.contactbook_error_email
import id.homebase.resources.contactbook_error_forbidden
import id.homebase.resources.contactbook_error_photo
import id.homebase.resources.contactbook_error_save
import id.homebase.resources.menu_back
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.readBytes
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@Composable
fun AddContactScreen(
    viewModel: AddContactViewModel,
    connectRequestViewModel: ConnectRequestViewModel,
    onBack: () -> Unit,
    onOpenConversation: (Uuid) -> Unit,
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val errSave = stringResource(MR.string.contactbook_error_save)
    val errForbidden = stringResource(MR.string.contactbook_error_forbidden)
    val errPhoto = stringResource(MR.string.contactbook_error_photo)
    val msgAccepted = stringResource(MR.string.contactbook_action_request_accepted)
    val msgRejected = stringResource(MR.string.contactbook_action_request_rejected)
    val msgCancelled = stringResource(MR.string.contactbook_action_request_cancelled)
    val msgActionFailed = stringResource(MR.string.auto_connect_failed_generic)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                AddContactEvent.Saved, AddContactEvent.Back -> onBack()
                AddContactEvent.Forbidden -> snackbarHostState.showSnackbar(errForbidden)
                AddContactEvent.Error -> snackbarHostState.showSnackbar(errSave)
                AddContactEvent.PhotoFailed -> snackbarHostState.showSnackbar(errPhoto)
                // Request actions keep the user on the screen — the card reflects the new
                // relationship reactively, and we just confirm with a snackbar.
                AddContactEvent.RequestAccepted -> snackbarHostState.showSnackbar(msgAccepted)
                AddContactEvent.RequestRejected -> snackbarHostState.showSnackbar(msgRejected)
                AddContactEvent.RequestCancelled -> snackbarHostState.showSnackbar(msgCancelled)
                AddContactEvent.RequestActionFailed -> snackbarHostState.showSnackbar(msgActionFailed)
            }
        }
    }

    var photoBytes by remember { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(uiState.photo) {
        photoBytes = uiState.photo?.let { runCatching { it.readBytes() }.getOrNull() }
    }
    val photoPicker = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
        if (file != null) viewModel.onAction(AddContactAction.PhotoPicked(file))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.add_contact_title)) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.onAction(AddContactAction.BackClicked) }) {
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AddContactAvatar(uiState, photoBytes)
            // The photo picker only belongs to manual entry. In identity mode the avatar comes from
            // the Homebase profile, so we never prompt to add one — we just say where it's from once
            // the identity resolves.
            when {
                uiState.mode == AddContactMode.MANUAL -> {
                    TextButton(onClick = { photoPicker.launch() }) {
                        Icon(Icons.Outlined.AddAPhoto, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(stringResource(MR.string.contactbook_edit_change_photo))
                    }
                }

                uiState.resolution is RecipientResolution.Resolved -> {
                    Text(
                        text = stringResource(MR.string.add_contact_photo_from_profile),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            when (uiState.mode) {
                AddContactMode.BY_IDENTITY -> ByIdentitySection(
                    uiState = uiState,
                    onAction = viewModel::onAction,
                    onSendConnectionRequest = { odinId ->
                        connectRequestViewModel.onAction(
                            ConnectRequestAction.OpenDialogWithRecipient(odinId),
                        )
                    },
                )

                AddContactMode.MANUAL -> ManualSection(uiState, viewModel::onAction)
            }
        }

        ConnectRequestBottomSheet(
            viewModel = connectRequestViewModel,
            snackbarHostState = snackbarHostState,
            onNavigateToConversation = onOpenConversation,
        )
    }
}

@Composable
private fun AddContactAvatar(uiState: AddContactUiState, photoBytes: ByteArray?) {
    val size = 88.dp
    val resolution = uiState.resolution
    when {
        photoBytes != null -> AsyncImage(
            model = photoBytes,
            contentDescription = null,
            modifier = Modifier.size(size).clip(CircleShape),
        )
        resolution is RecipientResolution.Resolved -> ContactAvatar(
            odinId = resolution.identity.odinId,
            profileImageData = null,
            initials = resolution.identity.initials(),
            options = AvatarOptions(size = size),
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
private fun ByIdentitySection(
    uiState: AddContactUiState,
    onAction: (AddContactAction) -> Unit,
    onSendConnectionRequest: (OdinId) -> Unit,
) {
    val resolution = uiState.resolution
    AddField(
        value = uiState.draft.odinId,
        label = stringResource(MR.string.add_contact_odinid_label),
        placeholder = stringResource(MR.string.add_contact_odinid_hint),
        isError = resolution is RecipientResolution.InvalidFormat,
        supportingText = when (resolution) {
            RecipientResolution.InvalidFormat -> stringResource(MR.string.add_contact_invalid)
            RecipientResolution.Idle -> stringResource(MR.string.add_contact_lead_help)
            else -> null
        },
        keyboardType = KeyboardType.Uri,
        onChange = { onAction(AddContactAction.OdinIdChanged(it)) },
    )

    ResolutionIndicator(resolution)

    when (resolution) {
        // A resolved identity is presented read-only: the profile data we pulled, plus exactly
        // the connection action that currently applies (send / accept / reject / cancel /
        // already connected) and a one-tap "Save as new contact". We deliberately do NOT show
        // editable fields for data that came from their Homebase profile.
        is RecipientResolution.Resolved -> {
            ResolvedIdentityCard(resolution.identity)
            RelationActions(
                relation = uiState.relation,
                odinId = resolution.identity.odinId,
                actionInProgress = uiState.actionInProgress,
                onSendConnectionRequest = onSendConnectionRequest,
                onAction = onAction,
            )
            SaveAsNewRow(uiState, onAction)
        }

        // Couldn't resolve the ID, but the user can still add them by hand ("add anyway").
        RecipientResolution.NotFound -> {
            NameFields(uiState, onAction)
            Spacer(modifier = Modifier.height(8.dp))
            OptionalDetails(uiState, onAction)
            SaveButton(
                enabled = uiState.canSave,
                onClick = { onAction(AddContactAction.SaveClicked) },
            )
        }

        else -> {}
    }

    Spacer(modifier = Modifier.height(8.dp))
    TextButton(onClick = { onAction(AddContactAction.SwitchToManual) }) {
        Text(stringResource(MR.string.add_contact_manual_link))
    }
}

/** Read-only display of the data pulled from a resolved Homebase profile. */
@Composable
private fun ResolvedIdentityCard(identity: PublicIdentity) {
    Spacer(modifier = Modifier.height(4.dp))
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = identity.displayNameOrDomain(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = identity.odinId.domainName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        identity.status?.takeIf { it.isNotBlank() }?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** The single connection action applicable to the resolved identity's current relationship. */
@Composable
private fun RelationActions(
    relation: IdentityRelation,
    odinId: OdinId,
    actionInProgress: Boolean,
    onSendConnectionRequest: (OdinId) -> Unit,
    onAction: (AddContactAction) -> Unit,
) {
    when (relation) {
        IdentityRelation.NONE ->
            ConnectRequestOffer(onClick = { onSendConnectionRequest(odinId) })

        IdentityRelation.INCOMING_PENDING -> {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(MR.string.contactbook_detail_request_incoming),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onAction(AddContactAction.AcceptRequestClicked) },
                    enabled = !actionInProgress,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(MR.string.contactbook_detail_accept))
                }
                OutlinedButton(
                    onClick = { onAction(AddContactAction.RejectRequestClicked) },
                    enabled = !actionInProgress,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(MR.string.contactbook_detail_reject))
                }
            }
        }

        IdentityRelation.OUTGOING_PENDING -> {
            Spacer(modifier = Modifier.height(12.dp))
            IndicatorRow(
                content = {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                },
                text = stringResource(MR.string.contactbook_detail_request_outgoing),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { onAction(AddContactAction.CancelRequestClicked) },
                enabled = !actionInProgress,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(MR.string.contactbook_detail_cancel_request))
            }
        }

        IdentityRelation.CONNECTED -> AlreadyConnectedNote()

        IdentityRelation.BLOCKED -> {
            Spacer(modifier = Modifier.height(12.dp))
            IndicatorRow(
                content = {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                },
                text = stringResource(MR.string.add_contact_blocked),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Save as new contact" affordance, or a note if they're already in the book. */
@Composable
private fun SaveAsNewRow(
    uiState: AddContactUiState,
    onAction: (AddContactAction) -> Unit,
) {
    if (uiState.alreadySaved) {
        Spacer(modifier = Modifier.height(12.dp))
        IndicatorRow(
            content = {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            },
            text = stringResource(MR.string.add_contact_already_saved),
            tint = MaterialTheme.colorScheme.primary,
        )
        return
    }
    // Keep one primary (filled) button on screen: when the relationship already has a filled CTA
    // (Send request / Accept), Save is the secondary outlined action; otherwise it's primary.
    val saveSecondary = uiState.relation == IdentityRelation.NONE ||
        uiState.relation == IdentityRelation.INCOMING_PENDING
    Spacer(modifier = Modifier.height(8.dp))
    if (saveSecondary) {
        OutlinedButton(
            onClick = { onAction(AddContactAction.SaveClicked) },
            enabled = uiState.canSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(MR.string.add_contact_save_as_new))
        }
    } else {
        Button(
            onClick = { onAction(AddContactAction.SaveClicked) },
            enabled = uiState.canSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(MR.string.add_contact_save_as_new))
        }
    }
}

/** Full-width primary Save used by manual entry and the "add anyway" (NotFound) path. */
@Composable
private fun SaveButton(enabled: Boolean, onClick: () -> Unit) {
    Spacer(modifier = Modifier.height(24.dp))
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(MR.string.contactbook_edit_save))
    }
}

@Composable
private fun AlreadyConnectedNote() {
    Spacer(modifier = Modifier.height(8.dp))
    IndicatorRow(
        content = {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        },
        text = stringResource(MR.string.add_contact_already_connected),
        tint = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ConnectRequestOffer(onClick: () -> Unit) {
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(MR.string.add_contact_send_request_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.PersonAddAlt1, contentDescription = null)
        Spacer(modifier = Modifier.size(8.dp))
        Text(stringResource(MR.string.add_contact_send_request))
    }
}

@Composable
private fun ResolutionIndicator(resolution: RecipientResolution) {
    when (resolution) {
        RecipientResolution.Resolving -> IndicatorRow(
            content = { CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp) },
            text = stringResource(MR.string.add_contact_resolving),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // A resolved identity is rendered by ResolvedIdentityCard, not as a one-line indicator.
        is RecipientResolution.Resolved -> {}
        RecipientResolution.NotFound -> IndicatorRow(
            content = {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            },
            text = stringResource(MR.string.add_contact_not_found),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RecipientResolution.Idle, RecipientResolution.InvalidFormat -> {}
    }
}

@Composable
private fun IndicatorRow(
    content: @Composable () -> Unit,
    text: String,
    tint: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

@Composable
private fun ManualSection(
    uiState: AddContactUiState,
    onAction: (AddContactAction) -> Unit,
) {
    NameFields(uiState, onAction)
    OptionalDetails(uiState, onAction)
    SaveButton(
        enabled = uiState.canSave,
        onClick = { onAction(AddContactAction.SaveClicked) },
    )
    Spacer(modifier = Modifier.height(8.dp))
    TextButton(onClick = { onAction(AddContactAction.SwitchToByIdentity) }) {
        Text(stringResource(MR.string.add_contact_byid_link))
    }
}

@Composable
private fun NameFields(
    uiState: AddContactUiState,
    onAction: (AddContactAction) -> Unit,
) {
    val draft = uiState.draft
    AddField(
        value = draft.givenName,
        label = stringResource(MR.string.contactbook_edit_given_name),
        onChange = { onAction(AddContactAction.DraftChanged(draft.copy(givenName = it))) },
    )
    AddField(
        value = draft.surname,
        label = stringResource(MR.string.contactbook_edit_surname),
        onChange = { onAction(AddContactAction.DraftChanged(draft.copy(surname = it))) },
    )
}

@Composable
private fun OptionalDetails(
    uiState: AddContactUiState,
    onAction: (AddContactAction) -> Unit,
) {
    val draft = uiState.draft
    Text(
        text = stringResource(MR.string.add_contact_details_optional),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
    )
    PhoneNumberField(
        e164Value = draft.phone,
        onValueChange = { onAction(AddContactAction.DraftChanged(draft.copy(phone = it))) },
        label = stringResource(MR.string.contactbook_edit_phone),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
    AddField(
        value = draft.email,
        label = stringResource(MR.string.contactbook_edit_email),
        isError = !draft.emailValid && draft.email.isNotBlank(),
        supportingText = stringResource(MR.string.contactbook_error_email)
            .takeIf { !draft.emailValid && draft.email.isNotBlank() },
        keyboardType = KeyboardType.Email,
        onChange = { onAction(AddContactAction.DraftChanged(draft.copy(email = it))) },
    )
    AddField(
        value = draft.city,
        label = stringResource(MR.string.contactbook_edit_city),
        onChange = { onAction(AddContactAction.DraftChanged(draft.copy(city = it))) },
    )
    AddField(
        value = draft.country,
        label = stringResource(MR.string.contactbook_edit_country),
        onChange = { onAction(AddContactAction.DraftChanged(draft.copy(country = it))) },
    )
}

@Composable
private fun AddField(
    value: String,
    label: String,
    placeholder: String? = null,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        supportingText = supportingText?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
