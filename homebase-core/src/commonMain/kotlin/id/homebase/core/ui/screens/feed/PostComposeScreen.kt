package id.homebase.core.ui.screens.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
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
import id.homebase.resources.feed_compose_posting
import id.homebase.resources.feed_compose_remove_attachment
import id.homebase.resources.feed_compose_title
import id.homebase.resources.menu_back
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.Uuid

private val ComposerMaxWidth = 640.dp

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
                is PostComposeEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
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

    PostComposeContent(
        state = uiState,
        snackbarHostState = snackbarHostState,
        onClose = onClose,
        onPost = viewModel::submit,
        onCaptionChange = viewModel::onCaptionChange,
        onAddMedia = { galleryLauncher.launch() },
        onCamera = { cameraLauncher.launch() },
        onRemoveAttachment = viewModel::removeAttachment,
        onSelectChannel = viewModel::selectChannel,
        onPickPublic = viewModel::pickPublic,
        onPickCircles = viewModel::pickCircles,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PostComposeContent(
    state: PostComposeUiState,
    snackbarHostState: SnackbarHostState,
    onClose: () -> Unit,
    onPost: () -> Unit,
    onCaptionChange: (String) -> Unit,
    onAddMedia: () -> Unit,
    onCamera: () -> Unit,
    onRemoveAttachment: (Uuid) -> Unit,
    onSelectChannel: (Uuid) -> Unit,
    onPickPublic: () -> Unit,
    onPickCircles: (Set<String>) -> Unit,
) {
    var circlesDialogOpen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            Box(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.TopCenter,
            ) {
                TopAppBar(
                    modifier = Modifier.widthIn(max = ComposerMaxWidth),
                    title = { Text(stringResource(MR.string.feed_compose_title)) },
                    navigationIcon = {
                        IconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(MR.string.menu_back),
                            )
                        }
                    },
                    actions = { PostButton(state = state, onPost = onPost) },
                )
                if (state.isPosting) {
                    LinearWavyProgressIndicator(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    )
                }
            }
        },
        bottomBar = {
            ComposerActionBar(
                state = state,
                onAddMedia = onAddMedia,
                onCamera = onCamera,
                onSelectChannel = onSelectChannel,
                onPickPublic = onPickPublic,
                onPickCircles = { circlesDialogOpen = true },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = ComposerMaxWidth)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                state.selfOdinId?.let { self ->
                    val authorName = state.selfName?.takeIf { it.isNotBlank() } ?: self.domainName
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IdentityAvatar(
                            odinId = self,
                            displayName = authorName,
                            options = AvatarOptions(size = 48.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = authorName,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (authorName != self.domainName) {
                                Text(
                                    text = self.domainName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                CaptionField(
                    value = state.caption,
                    onValueChange = onCaptionChange,
                    enabled = !state.isPosting,
                    modifier = Modifier.heightIn(min = if (state.attachments.isEmpty()) 160.dp else 72.dp),
                )

                if (state.attachments.isNotEmpty()) {
                    AttachmentThumbnailRow(
                        attachments = state.attachments,
                        enabled = !state.isPosting,
                        onRemove = onRemoveAttachment,
                    )
                }
            }
        }
    }

    if (circlesDialogOpen) {
        CirclesDialog(
            circles = state.circles,
            initiallySelected = state.selectedCircleIds,
            onConfirm = {
                circlesDialogOpen = false
                onPickCircles(it)
            },
            onDismiss = { circlesDialogOpen = false },
        )
    }
}

@Composable
private fun CaptionField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        text = stringResource(MR.string.feed_compose_caption_placeholder),
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ComposerActionBar(
    state: PostComposeUiState,
    onAddMedia: () -> Unit,
    onCamera: () -> Unit,
    onSelectChannel: (Uuid) -> Unit,
    onPickPublic: () -> Unit,
    onPickCircles: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = ComposerMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(
                    onClick = onAddMedia,
                    enabled = !state.isPosting,
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AddPhotoAlternate,
                        contentDescription = stringResource(MR.string.feed_compose_add_media),
                    )
                }
                FilledTonalIconButton(
                    onClick = onCamera,
                    enabled = !state.isPosting,
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PhotoCamera,
                        contentDescription = stringResource(MR.string.feed_compose_camera),
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    ChannelChip(
                        channels = state.channels,
                        selectedChannelId = state.selectedChannelId,
                        enabled = !state.isPosting,
                        onSelect = onSelectChannel,
                    )
                    AudienceChip(
                        audience = state.audience,
                        circleCount = state.selectedCircleIds.size,
                        enabled = !state.isPosting,
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
    enabled: Boolean,
    onRemove: (Uuid) -> Unit,
) {
    val imageLoader: ImageLoader = koinInject()
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(attachments, key = { it.attachmentId }) { attachment ->
            Box(modifier = Modifier.size(132.dp)) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.align(Alignment.BottomStart).size(120.dp),
                ) {
                    AsyncImage(
                        imageLoader = imageLoader,
                        model = (attachment as? AttachmentPendingFile.FileImage)?.file,
                        contentDescription = stringResource(MR.string.feed_compose_attachment_image),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                if (enabled) {
                    IconButton(
                        onClick = { onRemove(attachment.attachmentId) },
                        modifier = Modifier.align(Alignment.TopEnd).size(32.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface),
                            modifier = Modifier.size(26.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
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
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PostButton(state: PostComposeUiState, onPost: () -> Unit) {
    Button(
        onClick = { if (!state.isPosting) onPost() },
        enabled = state.canPost || state.isPosting,
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.padding(end = 12.dp),
    ) {
        if (state.isPosting) {
            LoadingIndicator(modifier = Modifier.size(20.dp), color = LocalContentColor.current)
            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(MR.string.feed_compose_posting))
        } else {
            Text(stringResource(MR.string.feed_compose_post))
        }
    }
}

@Composable
private fun DropdownChip(
    label: String,
    leadingIcon: ImageVector,
    leadingIconDescription: String,
    enabled: Boolean,
    emphasized: Boolean,
    onClick: () -> Unit,
) {
    val colors = if (emphasized) {
        AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            trailingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    } else {
        AssistChipDefaults.assistChipColors(leadingIconContentColor = MaterialTheme.colorScheme.primary)
    }
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.extraLarge,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            Icon(
                imageVector = leadingIcon,
                contentDescription = leadingIconDescription,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
        trailingIcon = {
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        },
        colors = colors,
        border = if (emphasized) null else AssistChipDefaults.assistChipBorder(enabled),
    )
}

@Composable
private fun ChannelChip(
    channels: List<ChannelOption>,
    selectedChannelId: Uuid,
    enabled: Boolean,
    onSelect: (Uuid) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = channels.firstOrNull { it.id == selectedChannelId } ?: channels.firstOrNull() ?: return

    Box {
        DropdownChip(
            label = selected.name,
            leadingIcon = Icons.Outlined.Tag,
            leadingIconDescription = stringResource(MR.string.feed_compose_channel),
            enabled = enabled,
            emphasized = false,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            channels.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name) },
                    leadingIcon = { SelectedMark(option.id == selected.id) },
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
    enabled: Boolean,
    onPickPublic: () -> Unit,
    onPickCircles: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val circles = audience == PostAudience.Circles
    Box {
        DropdownChip(
            label = if (circles) {
                stringResource(MR.string.feed_compose_audience_circles_count, circleCount)
            } else {
                stringResource(MR.string.feed_compose_audience_public)
            },
            leadingIcon = if (circles) Icons.Outlined.Groups else Icons.Outlined.Public,
            leadingIconDescription = stringResource(MR.string.feed_compose_audience),
            enabled = enabled,
            emphasized = true,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(MR.string.feed_compose_audience_public)) },
                leadingIcon = { Icon(Icons.Outlined.Public, contentDescription = null) },
                trailingIcon = { SelectedMark(!circles) },
                onClick = {
                    expanded = false
                    onPickPublic()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(MR.string.feed_compose_audience_circles)) },
                leadingIcon = { Icon(Icons.Outlined.Groups, contentDescription = null) },
                trailingIcon = { SelectedMark(circles) },
                onClick = {
                    expanded = false
                    onPickCircles()
                },
            )
        }
    }
}

@Composable
private fun SelectedMark(selected: Boolean) {
    Icon(
        imageVector = Icons.Filled.Check,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.alpha(if (selected) 1f else 0f),
    )
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
        icon = { Icon(Icons.Outlined.Groups, contentDescription = null) },
        title = { Text(stringResource(MR.string.feed_compose_circles_title)) },
        text = {
            if (circles.isEmpty()) {
                Text(stringResource(MR.string.feed_compose_circles_empty))
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    circles.forEach { circle ->
                        val checked = circle.id in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clip(MaterialTheme.shapes.large)
                                .toggleable(value = checked, role = Role.Checkbox) {
                                    selected = if (it) selected + circle.id else selected - circle.id
                                }
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = circle.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
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
