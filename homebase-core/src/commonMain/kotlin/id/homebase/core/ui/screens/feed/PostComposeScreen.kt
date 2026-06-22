package id.homebase.core.ui.screens.feed

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.chat.widget.LinkPreviewCard
import id.homebase.core.feed.services.EmbeddedPost
import id.homebase.core.feed.services.ReactAccess
import id.homebase.core.util.rememberCameraManager
import id.homebase.resources.MR
import id.homebase.resources.feed_compose_audience_anyone
import id.homebase.resources.feed_compose_audience_connections
import id.homebase.resources.feed_compose_audience_owner
import id.homebase.resources.feed_compose_audience_registered
import id.homebase.resources.feed_compose_add_media
import id.homebase.resources.feed_compose_attachment_image
import id.homebase.resources.feed_compose_camera
import id.homebase.resources.feed_compose_caption_placeholder
import id.homebase.resources.feed_compose_channel
import id.homebase.resources.feed_compose_post
import id.homebase.resources.feed_compose_react_all
import id.homebase.resources.feed_compose_react_comment_only
import id.homebase.resources.feed_compose_react_emoji_only
import id.homebase.resources.feed_compose_react_none
import id.homebase.resources.feed_compose_remove_attachment
import id.homebase.resources.feed_compose_title
import id.homebase.resources.menu_back
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.mimeType
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

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                PostComposeEvent.Dismiss -> onClose()
                is PostComposeEvent.ShowSnackbar ->
                    event.message?.let { snackbarHostState.showSnackbar(it) }
            }
        }
    }

    val galleryLauncher = rememberFilePickerLauncher(
        type = FileKitType.ImageAndVideo,
        mode = FileKitMode.Multiple(),
    ) { files ->
        if (files.isNullOrEmpty()) return@rememberFilePickerLauncher
        viewModel.addAttachments(files.map { it.toPendingFile() })
    }

    val cameraLauncher = rememberCameraManager { file ->
        file?.let { viewModel.addAttachments(listOf(it.toPendingFile())) }
    }

    Scaffold(
        // Lift the whole composer above the keyboard so the Post bar isn't covered (matches
        // MomentComposeScreen / AddGroupMembersScreen).
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
            )
        },
        bottomBar = {
            PostComposeBottomBar(
                enabled = uiState.canPost,
                isPosting = uiState.isPosting,
                onPost = viewModel::submit,
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

            OutlinedTextField(
                value = uiState.caption,
                onValueChange = viewModel::onCaptionChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(MR.string.feed_compose_caption_placeholder)) },
                minLines = 3,
            )

            uiState.embeddedPost?.let { embedded ->
                QuotedPostPreview(embedded = embedded, modifier = Modifier.fillMaxWidth())
            }

            if (uiState.attachments.isNotEmpty()) {
                AttachmentThumbnailRow(
                    attachments = uiState.attachments,
                    onRemove = viewModel::removeAttachment,
                )
            }

            uiState.effectiveLinkPreview?.let { preview ->
                LinkPreviewCard(
                    linkPreview = preview,
                    isCompact = true,
                    onCancel = viewModel::clearLinkPreview,
                )
            }

            if (uiState.isFetchingLinkPreview && uiState.effectiveLinkPreview == null) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }

            // Toolbar: add media, camera, audience selector, react-access toggle.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { galleryLauncher.launch() }) {
                    Icon(
                        imageVector = Icons.Outlined.AddPhotoAlternate,
                        contentDescription = stringResource(MR.string.feed_compose_add_media),
                    )
                }
                IconButton(onClick = { cameraLauncher.launch() }) {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = stringResource(MR.string.feed_compose_camera),
                    )
                }
                Spacer(modifier = Modifier.size(4.dp))
                ChannelChip(
                    channels = uiState.channels,
                    selectedChannelId = uiState.selectedChannelId,
                    onSelect = viewModel::selectChannel,
                )
                AudienceChip(
                    audience = uiState.audience,
                    onCycle = { viewModel.pickAudience(uiState.audience.next()) },
                )
                ReactAccessChip(
                    reactAccess = uiState.reactAccess,
                    onCycle = viewModel::toggleReactAccess,
                )
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
                    // Coil sniffs content from a plaintext file path; videos fall back to their
                    // poster bytes when available, else the path's first frame on platforms that
                    // decode it.
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

/**
 * The source post being quoted, rendered as a bordered card above the composer toolbar. Matches
 * the inline `QuotedPost` block on [id.homebase.core.ui.screens.feed.widget.PostCard] so the
 * compose-time preview reads identically to how the published repost will render.
 */
@Composable
private fun QuotedPostPreview(
    embedded: EmbeddedPost,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            embedded.author?.takeIf { it.isNotBlank() }?.let { author ->
                Text(
                    text = author,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            embedded.caption?.takeIf { it.isNotBlank() }?.let { caption ->
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Channel picker: an [AssistChip] showing the selected channel's name; tapping opens a
 * [DropdownMenu] of the available channels. The public channel is always the first option.
 */
@Composable
private fun ChannelChip(
    channels: List<ChannelOption>,
    selectedChannelId: Uuid,
    onSelect: (Uuid) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // The selected option's name drives the label; fall back to the first option (public) until
    // the list resolves. A bare chip label with no name has nothing to show, so skip rendering.
    val selected = channels.firstOrNull { it.id == selectedChannelId } ?: channels.firstOrNull()
    if (selected == null) return

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
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
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
    audience: SecurityGroupType,
    onCycle: () -> Unit,
) {
    AssistChip(
        onClick = onCycle,
        label = { Text(stringResource(audience.labelRes())) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Public,
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
    )
}

@Composable
private fun ReactAccessChip(
    reactAccess: ReactAccess,
    onCycle: () -> Unit,
) {
    AssistChip(
        onClick = onCycle,
        label = { Text(stringResource(reactAccess.labelRes())) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.ThumbUp,
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
    )
}

@Composable
private fun PostComposeBottomBar(
    enabled: Boolean,
    isPosting: Boolean,
    onPost: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = onPost,
                    enabled = enabled,
                ) {
                    if (isPosting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(stringResource(MR.string.feed_compose_post))
                    }
                }
            }
        }
    }
}

// ─── Mapping helpers ─────────────────────────────────────────────────────────

private fun io.github.vinceglb.filekit.PlatformFile.toPendingFile(): AttachmentPendingFile {
    val ct = mimeType()?.toString().orEmpty()
    return if (ct.startsWith("video/")) {
        AttachmentPendingFile.FileVideo(Uuid.generateV7(), this, thumbnailBytes = null)
    } else {
        AttachmentPendingFile.FileImage(Uuid.generateV7(), this)
    }
}

/** Coil model for a pending attachment's thumbnail: file path for images, poster bytes for video. */
private fun AttachmentPendingFile.previewModel(): Any? = when (this) {
    is AttachmentPendingFile.FileImage -> file.toString()
    is AttachmentPendingFile.FileVideo -> thumbnailBytes ?: file.toString()
    is AttachmentPendingFile.Gallery -> image.file.toString()
    is AttachmentPendingFile.File -> file.toString()
    is AttachmentPendingFile.Audio -> null
}

private fun SecurityGroupType.next(): SecurityGroupType = when (this) {
    SecurityGroupType.Anonymous -> SecurityGroupType.Authenticated
    SecurityGroupType.Authenticated -> SecurityGroupType.Connected
    SecurityGroupType.Connected -> SecurityGroupType.Owner
    SecurityGroupType.Owner -> SecurityGroupType.Anonymous
    SecurityGroupType.AutoConnected -> SecurityGroupType.Anonymous
}

private fun SecurityGroupType.labelRes() = when (this) {
    SecurityGroupType.Anonymous -> MR.string.feed_compose_audience_anyone
    SecurityGroupType.Authenticated -> MR.string.feed_compose_audience_registered
    SecurityGroupType.Connected, SecurityGroupType.AutoConnected ->
        MR.string.feed_compose_audience_connections
    SecurityGroupType.Owner -> MR.string.feed_compose_audience_owner
}

private fun ReactAccess.labelRes() = when (this) {
    ReactAccess.All -> MR.string.feed_compose_react_all
    ReactAccess.EmojiOnly -> MR.string.feed_compose_react_emoji_only
    ReactAccess.CommentOnly -> MR.string.feed_compose_react_comment_only
    ReactAccess.None -> MR.string.feed_compose_react_none
}
