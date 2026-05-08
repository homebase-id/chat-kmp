package id.homebase.core.ui.screens.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.core.ui.screens.moments.widget.MomentDescriptionField
import id.homebase.core.ui.screens.moments.widget.MomentFullScreenEditor
import id.homebase.core.util.rememberCameraManager
import id.homebase.resources.MR
import id.homebase.resources.chat_message_add_gallery_image
import id.homebase.resources.menu_back
import id.homebase.resources.moments_compose_continue
import id.homebase.resources.moments_compose_empty_hero
import id.homebase.resources.moments_compose_title
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.mimeType
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentComposeScreen(
    viewModel: MomentComposeViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToAudience: () -> Unit,
    onNavigateToCropper: (Uuid) -> Unit,
    onNavigateToDrawer: (Uuid) -> Unit,
    onSaveFileToDevice: (filePath: String, fileName: String) -> Unit = { _, _ -> },
    onShowError: (String) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val textFieldState: RichTextState = rememberRichTextState()

    // Two-way sync between the rich text editor and the VM's plain-string
    // description. The editor owns the live state; we mirror toMarkdown() into
    // the VM whenever the user types so navigation/draft persistence sees the
    // current text without forcing a recomposition through the VM on every
    // keystroke.
    //
    // On first composition we seed the editor from the VM's description so a
    // back-nav from the audience picker rehydrates the text. The seed runs
    // before the snapshotFlow collector starts so the initial emission matches
    // what the VM already has — no clobber of the restored description.
    LaunchedEffect(textFieldState) {
        val restored = viewModel.uiState.value.description
        if (restored.isNotEmpty()) {
            textFieldState.setMarkdown(restored)
        }
        snapshotFlow { textFieldState.toMarkdown() }
            .distinctUntilChanged()
            .collect { md ->
                viewModel.onAction(MomentComposeUiAction.DescriptionChanged(md.trimEnd()))
            }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                MomentComposeUiEvent.NavigateToAudience -> onNavigateToAudience()
                is MomentComposeUiEvent.NavigateToCropper -> onNavigateToCropper(event.requestId)
                is MomentComposeUiEvent.NavigateToDrawer -> onNavigateToDrawer(event.requestId)
                is MomentComposeUiEvent.SaveFileToDevice ->
                    onSaveFileToDevice(event.filePath, event.fileName)

                is MomentComposeUiEvent.ShowError -> onShowError(event.message)
            }
        }
    }

    val galleryLauncher = rememberFilePickerLauncher(type = FileKitType.ImageAndVideo) { file ->
        file?.let {
            val ct = it.mimeType()?.toString().orEmpty()
            val pending = when {
                ct.startsWith("video/") ->
                    AttachmentPendingFile.FileVideo(Uuid.generateV7(), it, thumbnailBytes = null)

                else -> AttachmentPendingFile.FileImage(Uuid.generateV7(), it)
            }
            viewModel.onAction(MomentComposeUiAction.AttachmentsAdded(listOf(pending)))
        }
    }

    val fileLauncher = rememberFilePickerLauncher { file ->
        file?.let {
            viewModel.onAction(
                MomentComposeUiAction.AttachmentsAdded(
                    listOf(AttachmentPendingFile.File(Uuid.generateV7(), it))
                )
            )
        }
    }

    val cameraLauncher = rememberCameraManager { file ->
        file?.let {
            val ct = it.mimeType()?.toString().orEmpty()
            val pending = if (ct.startsWith("video/")) {
                AttachmentPendingFile.FileVideo(Uuid.generateV7(), it, thumbnailBytes = null)
            } else {
                AttachmentPendingFile.FileImage(Uuid.generateV7(), it)
            }
            viewModel.onAction(MomentComposeUiAction.AttachmentsAdded(listOf(pending)))
        }
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding),
        ) {
            if (uiState.attachments.isEmpty()) {
                EmptyComposeState(
                    textFieldState = textFieldState,
                    onAddImage = { galleryLauncher.launch() },
                    onCameraClick = { cameraLauncher.launch() },
                )
            } else {
                MomentFullScreenEditor(
                    attachments = uiState.attachments,
                    textFieldState = textFieldState,
                    currentPage = uiState.currentPage,
                    onPageChanged = { viewModel.onAction(MomentComposeUiAction.PageChanged(it)) },
                    onSaveFile = { viewModel.onAction(MomentComposeUiAction.SaveFile(it)) },
                    onAddFile = { fileLauncher.launch() },
                    onAddImage = { galleryLauncher.launch() },
                    onCameraClick = { cameraLauncher.launch() },
                    onRemoveFile = { id ->
                        viewModel.onAction(MomentComposeUiAction.AttachmentRemoved(id))
                    },
                    onCropImage = { id ->
                        viewModel.onAction(MomentComposeUiAction.RequestCrop(id))
                    },
                    onDrawImage = { id ->
                        viewModel.onAction(MomentComposeUiAction.RequestDraw(id))
                    },
                    onTrimChange = { id, startMs, endMs ->
                        viewModel.onAction(MomentComposeUiAction.ApplyTrim(id, startMs, endMs))
                    },
                    onToggleIncludeLocation = { id ->
                        viewModel.onAction(MomentComposeUiAction.ToggleIncludeLocation(id))
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyComposeState(
    textFieldState: RichTextState,
    onAddImage: () -> Unit,
    onCameraClick: () -> Unit,
) {
    // Skeleton mirrors `MomentFullScreenEditor`: hero placeholder where the
    // pager would render, the same camera + add strip row, and the same
    // composer at the bottom. The composer stays usable while empty so the
    // user can pre-write a description before picking media — its state
    // survives the empty → populated transition because it lives in the
    // screen, not in either branch.
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(16.dp),
                )
                .clickable(onClick = onAddImage),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(64.dp),
                )
                Text(
                    text = stringResource(MR.string.moments_compose_empty_hero),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Strip row: camera + add. Same look as the populated editor's
        // trailing controls so the layout doesn't shift on first attach.
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onCameraClick,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoCamera,
                    contentDescription = stringResource(MR.string.chat_message_add_gallery_image),
                )
            }
            IconButton(
                onClick = onAddImage,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(MR.string.chat_message_add_gallery_image),
                )
            }
        }

        // Reserve the vertical space the edit-tools row occupies in the
        // populated editor so the composer doesn't jump up when the first
        // attachment lands. Empty visually, but keeps layout stable.
        Spacer(modifier = Modifier.height(48.dp))

        MomentDescriptionField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .imePadding(),
            state = textFieldState,
        )
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
