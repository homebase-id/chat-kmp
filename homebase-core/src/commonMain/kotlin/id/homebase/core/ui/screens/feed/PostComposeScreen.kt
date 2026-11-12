package id.homebase.core.ui.screens.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.IdentityAvatar
import id.homebase.core.feed.services.PostAudience
import id.homebase.core.util.contentType
import id.homebase.core.util.rememberCameraManager
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.done
import id.homebase.resources.feed_compose_add_media
import id.homebase.resources.feed_compose_attachment_image
import id.homebase.resources.feed_compose_audience
import id.homebase.resources.feed_compose_audience_circles
import id.homebase.resources.feed_compose_audience_circles_count
import id.homebase.resources.feed_compose_audience_public
import id.homebase.resources.feed_compose_camera
import id.homebase.resources.feed_compose_caption_placeholder
import id.homebase.resources.feed_compose_channel
import id.homebase.resources.feed_compose_circles_empty
import id.homebase.resources.feed_compose_circles_title
import id.homebase.resources.feed_compose_post
import id.homebase.resources.feed_compose_remove_attachment
import id.homebase.resources.feed_compose_title
import id.homebase.resources.menu_back
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostComposeScreen(
    viewModel: PostComposeViewModel = koinViewModel(),
    onClose: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var circlesDialogOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                PostComposeEvent.Dismiss -> onClose()
                is PostComposeEvent.ShowSnackbar -> event.message?.let { snackbarHostState.showSnackbar(it) }
            }
        }
    }

    val galleryLauncher = rememberFilePickerLauncher(
        type = FileKitType.Image,
        mode = FileKitMode.Multiple(),
    ) { files ->
        if (files.isNullOrEmpty()) return@rememberFilePickerLauncher
        viewModel.addAttachments(files.map { it.toPendingImage() })
    }
    val cameraLauncher = rememberCameraManager { file ->
        file?.let { viewModel.addAttachments(listOf(it.toPendingImage())) }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.feed_compose_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = viewModel::submit,
                        enabled = uiState.canPost,
                        modifier = Modifier.padding(end = 12.dp),
                    ) {
                        if (uiState.isPosting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(stringResource(MR.string.feed_compose_post))
                        }
                    }
                },
            )
        },
        bottomBar = {
            ComposerActionBar(
                state = uiState,
                onAddMedia = { galleryLauncher.launch() },
                onCamera = { cameraLauncher.launch() },
                onSelectChannel = viewModel::selectChannel,
                onPickPublic = viewModel::pickPublic,
                onPickCircles = { circlesDialogOpen = true },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            uiState.selfOdinId?.let { self ->
                val authorName = uiState.selfName?.takeIf { it.isNotBlank() } ?: self.domainName
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IdentityAvatar(
                        odinId = self,
                        displayName = authorName,
                        options = AvatarOptions(size = 40.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = authorName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            TextField(
                value = uiState.caption,
                onValueChange = viewModel::onCaptionChange,
                modifier = Modifier.fillMaxWidth().weight(1f),
                placeholder = {
                    Text(
                        text = stringResource(MR.string.feed_compose_caption_placeholder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                textStyle = MaterialTheme.typography.bodyLarge,
                minLines = 3,
                enabled = !uiState.isPosting,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )

            if (uiState.attachments.isNotEmpty()) {
                AttachmentThumbnailRow(
                    attachments = uiState.attachments,
                    onRemove = viewModel::removeAttachment,
                )
            }
        }
    }

    if (circlesDialogOpen) {
        CirclesDialog(
            circles = uiState.circles,
            initiallySelected = uiState.selectedCircleIds,
            onConfirm = {
                circlesDialogOpen = false
                viewModel.pickCircles(it)
            },
            onDismiss = { circlesDialogOpen = false },
        )
    }
}

@Composable
private fun ComposerActionBar(
    state: PostComposeUiState,
    onAddMedia: () -> Unit,
    onCamera: () -> Unit,
    onSelectChannel: (Uuid) -> Unit,
    onPickPublic: () -> Unit,
    onPickCircles: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onAddMedia, enabled = !state.isPosting) {
                    Icon(
                        imageVector = Icons.Outlined.AddPhotoAlternate,
                        contentDescription = stringResource(MR.string.feed_compose_add_media),
                    )
                }
                IconButton(onClick = onCamera, enabled = !state.isPosting) {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = stringResource(MR.string.feed_compose_camera),
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChannelChip(
                        channels = state.channels,
                        selectedChannelId = state.selectedChannelId,
                        onSelect = onSelectChannel,
                    )
                    AudienceChip(
                        audience = state.audience,
                        circleCount = state.selectedCircleIds.size,
                        onPickPublic = onPickPublic,
                        onPickCircles = onPickCircles,
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentThumbnailRow(
    attachments: List<AttachmentPendingFile>,
    onRemove: (Uuid) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(attachments, key = { it.attachmentId }) { attachment ->
            Box(modifier = Modifier.size(96.dp)) {
                AsyncImage(
                    model = attachment.previewModel(),
                    contentDescription = stringResource(MR.string.feed_compose_attachment_image),
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentScale = ContentScale.Crop,
                )
                IconButton(
                    onClick = { onRemove(attachment.attachmentId) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(24.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(MR.string.feed_compose_remove_attachment),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelChip(
    channels: List<ChannelOption>,
    selectedChannelId: Uuid,
    onSelect: (Uuid) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = channels.firstOrNull { it.id == selectedChannelId } ?: channels.firstOrNull() ?: return

    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(selected.name) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Tag,
                    contentDescription = stringResource(MR.string.feed_compose_channel),
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            channels.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name) },
                    onClick = {
                        expanded = false
                        onSelect(option.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun AudienceChip(
    audience: PostAudience,
    circleCount: Int,
    onPickPublic: () -> Unit,
    onPickCircles: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val value = if (audience == PostAudience.Circles) {
        stringResource(MR.string.feed_compose_audience_circles_count, circleCount)
    } else {
        stringResource(MR.string.feed_compose_audience_public)
    }
    val label = "${stringResource(MR.string.feed_compose_audience)}: $value"
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(label) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Public,
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(MR.string.feed_compose_audience_public)) },
                leadingIcon = {
                    if (audience == PostAudience.Public) Icon(Icons.Filled.Check, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    onPickPublic()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(MR.string.feed_compose_audience_circles)) },
                leadingIcon = {
                    if (audience == PostAudience.Circles) Icon(Icons.Filled.Check, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    onPickCircles()
                },
            )
        }
    }
}

@Composable
private fun CirclesDialog(
    circles: List<CircleOption>,
    initiallySelected: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(initiallySelected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.string.feed_compose_circles_title)) },
        text = {
            if (circles.isEmpty()) {
                Text(stringResource(MR.string.feed_compose_circles_empty))
            } else {
                Column {
                    circles.forEach { circle ->
                        val checked = circle.id in selected
                        val toggle = { selected = if (checked) selected - circle.id else selected + circle.id }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = toggle),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { toggle() })
                            Text(circle.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }, enabled = circles.isNotEmpty()) {
                Text(stringResource(MR.string.done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.string.cancel)) }
        },
    )
}

private fun PlatformFile.toPendingImage(): AttachmentPendingFile =
    AttachmentPendingFile.FileImage(Uuid.generateV7(), this, sourceContentType = contentType())

private fun AttachmentPendingFile.previewModel(): Any? = when (this) {
    is AttachmentPendingFile.FileImage -> file.toString()
    is AttachmentPendingFile.FileVideo -> thumbnailBytes ?: file.toString()
    is AttachmentPendingFile.Gallery -> image.file.toString()
    is AttachmentPendingFile.File -> file.toString()
    is AttachmentPendingFile.Audio -> null
}
