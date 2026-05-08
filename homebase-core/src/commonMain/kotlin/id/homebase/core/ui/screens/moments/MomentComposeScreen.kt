package id.homebase.core.ui.screens.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.moments_compose_add_media
import id.homebase.resources.moments_compose_comments_enabled
import id.homebase.resources.moments_compose_continue
import id.homebase.resources.moments_compose_description_hint
import id.homebase.resources.moments_compose_remove_media
import id.homebase.resources.moments_compose_title
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentComposeScreen(
    viewModel: MomentComposeViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToAudience: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                MomentComposeUiEvent.NavigateToAudience -> onNavigateToAudience()
            }
        }
    }

    val mediaPicker = rememberFilePickerLauncher(type = FileKitType.ImageAndVideo) { file ->
        file ?: return@rememberFilePickerLauncher
        val contentType = file.mimeType()?.toString() ?: guessContentType(file.name)
        viewModel.onAction(
            MomentComposeUiAction.AttachmentsAdded(
                listOf(
                    AttachmentInput(
                        filePath = file.path,
                        contentType = contentType,
                        displayName = file.name,
                    ),
                ),
            ),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.moments_compose_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            ComposeBottomBar(
                enabled = uiState.canContinue,
                onContinue = { viewModel.onAction(MomentComposeUiAction.NextClicked) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MediaStrip(
                attachments = uiState.attachments,
                videoThumbnails = uiState.videoThumbnails,
                onAdd = { mediaPicker.launch() },
                onRemove = { path ->
                    viewModel.onAction(MomentComposeUiAction.AttachmentRemoved(path))
                },
            )

            OutlinedTextField(
                value = uiState.description,
                onValueChange = {
                    viewModel.onAction(MomentComposeUiAction.DescriptionChanged(it))
                },
                placeholder = { Text(stringResource(MR.string.moments_compose_description_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 96.dp),
                minLines = 3,
                maxLines = 8,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(MR.string.moments_compose_comments_enabled),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = uiState.commentsEnabled,
                    onCheckedChange = {
                        viewModel.onAction(MomentComposeUiAction.CommentsEnabledChanged(it))
                    },
                )
            }
        }
    }
}

@Composable
private fun MediaStrip(
    attachments: List<AttachmentInput>,
    videoThumbnails: Map<String, ByteArray>,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    if (attachments.isEmpty()) {
        OutlinedButton(
            onClick = onAdd,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = stringResource(MR.string.moments_compose_add_media),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    } else {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(attachments, key = { it.filePath }) { attachment ->
                AttachmentThumb(
                    attachment = attachment,
                    videoThumbnailBytes = videoThumbnails[attachment.filePath],
                    onRemove = { onRemove(attachment.filePath) },
                )
            }
            item {
                AddMoreCell(onClick = onAdd)
            }
        }
    }
}

@Composable
private fun AttachmentThumb(
    attachment: AttachmentInput,
    videoThumbnailBytes: ByteArray?,
    onRemove: () -> Unit,
) {
    val contentType = attachment.contentType
    val isImage = contentType.startsWith("image/")
    val isVideo = contentType.startsWith("video/") ||
            contentType == "application/vnd.apple.mpegurl"

    Box(
        modifier = Modifier
            .size(120.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        when {
            isImage -> {
                AsyncImage(
                    model = attachment.filePath,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            isVideo -> {
                // Poster frame extracted async via VideoThumbnailExtractor — the
                // bytes land in uiState.videoThumbnails once ready. Until then,
                // show a blank surface tile; the play overlay still renders so
                // the user knows it's a video.
                if (videoThumbnailBytes != null) {
                    AsyncImage(
                        model = videoThumbnailBytes,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Icon(
                    imageVector = Icons.Default.PlayCircle,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp),
                )
            }

            else -> {
                // Fallback for non-image/non-video uploads (shouldn't happen
                // through the ImageAndVideo file picker, but keep the row
                // tappable just in case).
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = attachment.displayName
                            ?: attachment.filePath.substringAfterLast('/'),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f)),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(MR.string.moments_compose_remove_media),
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun AddMoreCell(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .size(120.dp),
    ) {
        Icon(imageVector = Icons.Default.Add, contentDescription = null)
    }
}

@Composable
private fun ComposeBottomBar(
    enabled: Boolean,
    onContinue: () -> Unit,
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
                    onClick = onContinue,
                    enabled = enabled,
                ) {
                    Text(stringResource(MR.string.moments_compose_continue))
                }
            }
        }
    }
}

private fun guessContentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "heic" -> "image/heic"
    "webp" -> "image/webp"
    "mp4", "m4v" -> "video/mp4"
    "mov" -> "video/quicktime"
    "webm" -> "video/webm"
    else -> "application/octet-stream"
}
