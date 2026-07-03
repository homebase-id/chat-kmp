@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.chat.widget.MediaAttachmentEditor
import id.homebase.core.image.HomebaseImage
import id.homebase.resources.MR
import id.homebase.resources.cd_profile_avatar_change_photo
import id.homebase.resources.menu_back
import id.homebase.resources.profile_avatar_edit_acl_anonymous
import id.homebase.resources.profile_avatar_edit_acl_connected
import id.homebase.resources.profile_avatar_edit_anonymous_desc
import id.homebase.resources.profile_avatar_edit_connected_desc
import id.homebase.resources.profile_avatar_edit_error_delete
import id.homebase.resources.profile_avatar_edit_error_too_large
import id.homebase.resources.profile_avatar_edit_error_upload
import id.homebase.resources.profile_avatar_edit_remove
import id.homebase.resources.profile_avatar_edit_title
import id.homebase.resources.profile_avatar_edit_upload
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Composable
fun ProfileAvatarEditScreen(
    viewModel: ProfileAvatarEditViewModel,
    onBack: () -> Unit,
    onNavigateToCropper: (Uuid) -> Unit,
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val errUpload = stringResource(MR.string.profile_avatar_edit_error_upload)
    val errTooLarge = stringResource(MR.string.profile_avatar_edit_error_too_large)
    val errDelete = stringResource(MR.string.profile_avatar_edit_error_delete)

    val anonymousPicker = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
        file?.let { viewModel.onAction(ProfileAvatarEditAction.PhotoPicked(ProfileVisibility.ANONYMOUS, it)) }
    }
    val connectedPicker = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
        file?.let { viewModel.onAction(ProfileAvatarEditAction.PhotoPicked(ProfileVisibility.CONNECTED, it)) }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ProfileAvatarEditEvent.NavigateToCropper -> onNavigateToCropper(event.requestId)
                ProfileAvatarEditEvent.Back -> onBack()
                is ProfileAvatarEditEvent.UploadFailed -> snackbarHostState.showSnackbar(errUpload)
                is ProfileAvatarEditEvent.UploadTooLarge -> snackbarHostState.showSnackbar(errTooLarge)
                is ProfileAvatarEditEvent.DeleteFailed -> snackbarHostState.showSnackbar(errDelete)
            }
        }
    }

    // Only one tier can have a photo mid-pick/crop at a time — render whichever is active.
    val editingTier = uiState.anonymous.takeIf { it.pendingSourceAttachment != null }
        ?: uiState.connected.takeIf { it.pendingSourceAttachment != null }
    if (editingTier != null) {
        MediaAttachmentEditor(
            attachments = listOf(editingTier.pendingSourceAttachment!!),
            currentPage = 0,
            onPageChanged = {},
            onCropImage = { attachmentId ->
                viewModel.onAction(ProfileAvatarEditAction.CropRequested(editingTier.visibility, attachmentId))
            },
            onDismiss = {
                viewModel.onAction(ProfileAvatarEditAction.PhotoEditorDismissed(editingTier.visibility))
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.profile_avatar_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.onAction(ProfileAvatarEditAction.BackClicked) }) {
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
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            PhotoTierSection(
                title = stringResource(MR.string.profile_avatar_edit_acl_anonymous),
                description = stringResource(MR.string.profile_avatar_edit_anonymous_desc),
                tier = uiState.anonymous,
                onPick = { anonymousPicker.launch() },
                onRemove = { viewModel.onAction(ProfileAvatarEditAction.DeleteClicked(ProfileVisibility.ANONYMOUS)) },
                onSaveClicked = { viewModel.onAction(ProfileAvatarEditAction.UploadClicked(ProfileVisibility.ANONYMOUS)) },
            ) {
                val imageData = uiState.anonymous.existing?.photoImageData()
                if (imageData != null) {
                    HomebaseImage(
                        imageData = imageData,
                        modifier = Modifier.fillMaxSize(),
                        contentDescription = stringResource(MR.string.cd_profile_avatar_change_photo),
                    )
                } else {
                    EmptyAvatarPlaceholder()
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            PhotoTierSection(
                title = stringResource(MR.string.profile_avatar_edit_acl_connected),
                description = stringResource(MR.string.profile_avatar_edit_connected_desc),
                tier = uiState.connected,
                onPick = { connectedPicker.launch() },
                onRemove = { viewModel.onAction(ProfileAvatarEditAction.DeleteClicked(ProfileVisibility.CONNECTED)) },
                onSaveClicked = { viewModel.onAction(ProfileAvatarEditAction.UploadClicked(ProfileVisibility.CONNECTED)) },
            ) {
                val imageData = uiState.connected.existing?.photoImageData()
                if (imageData != null) {
                    HomebaseImage(
                        imageData = imageData,
                        modifier = Modifier.fillMaxSize(),
                        contentDescription = stringResource(MR.string.cd_profile_avatar_change_photo),
                    )
                } else {
                    EmptyAvatarPlaceholder()
                }
            }
        }
    }
}

/**
 * One [ProfileVisibility] tier's photo slot: a circular preview (tap to pick a new photo),
 * a "Remove" action when a photo is currently stored, and a Save button once a crop is pending.
 * [existingPhotoContent] renders the currently-stored photo (both tiers render the same way — an
 * authenticated/decrypted [id.homebase.core.image.HomebaseImageData] fetch — the callback just
 * keeps this composable itself agnostic to that).
 */
@Composable
private fun PhotoTierSection(
    title: String,
    description: String,
    tier: PhotoTierUiState,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    onSaveClicked: () -> Unit,
    existingPhotoContent: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(modifier = Modifier.size(96.dp).clip(CircleShape).clickable(onClick = onPick)) {
                    val cropped = tier.pendingCroppedAvatar
                    when {
                        cropped != null -> AsyncImage(
                            model = cropped.file,
                            contentDescription = stringResource(MR.string.cd_profile_avatar_change_photo),
                            modifier = Modifier.fillMaxSize(),
                        )
                        tier.existing != null -> existingPhotoContent()
                        else -> EmptyAvatarPlaceholder()
                    }
                }
                FilledIconButton(onClick = onPick, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.PhotoCamera,
                        contentDescription = stringResource(MR.string.cd_profile_avatar_change_photo),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            if (tier.pendingCroppedAvatar == null && tier.existing != null) {
                TextButton(onClick = onRemove, enabled = !tier.isDeleting) {
                    if (tier.isDeleting) {
                        CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(MR.string.profile_avatar_edit_remove))
                    }
                }
            }
        }

        if (tier.pendingCroppedAvatar != null) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onSaveClicked,
                enabled = tier.canUpload,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (tier.isUploading) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(MR.string.profile_avatar_edit_upload))
                }
            }
        }
    }
}

@Composable
private fun EmptyAvatarPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
    }
}
