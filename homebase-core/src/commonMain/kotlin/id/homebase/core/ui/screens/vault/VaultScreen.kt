@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.vault

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import id.homebase.core.HomebaseConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.backhandler.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.core.ui.screens.vault.auth.VaultBiometricGate
import id.homebase.core.ui.screens.vault.components.VaultAddSectionControl
import id.homebase.core.ui.screens.vault.components.VaultAddEntrySheet
import id.homebase.core.ui.screens.vault.components.VaultNewSectionSheet
import id.homebase.core.ui.screens.vault.gallery.VaultGalleryScreen
import id.homebase.core.ui.screens.vault.model.VaultEntry
import id.homebase.core.ui.screens.vault.model.VaultSection
import id.homebase.core.util.getUriHandler
import id.homebase.core.util.rememberCameraManager
import id.homebase.core.vault.VaultPreferences
import id.homebase.resources.MR
import id.homebase.resources.vault_error_append_pages
import id.homebase.resources.vault_error_create_section
import id.homebase.resources.vault_error_delete_file
import id.homebase.resources.vault_error_delete_page
import id.homebase.resources.vault_error_delete_section
import id.homebase.resources.vault_error_download
import id.homebase.resources.vault_error_download_page
import id.homebase.resources.vault_error_outbox_upload
import id.homebase.resources.vault_error_rename_file
import id.homebase.resources.vault_error_rename_section
import id.homebase.resources.vault_error_save_notes
import id.homebase.resources.vault_error_update_label
import id.homebase.resources.vault_error_upload
import id.homebase.resources.vault_label
import id.homebase.resources.vault_permission_cancel
import id.homebase.resources.vault_rename_action
import id.homebase.resources.vault_rename_title
import id.homebase.resources.vault_section_delete
import id.homebase.resources.vault_section_delete_confirm_message
import id.homebase.resources.vault_section_delete_confirm_title
import id.homebase.resources.vault_section_rename
import id.homebase.resources.vault_settings
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun VaultScreen(
    vaultExtendPermissionViewModel: ExtendPermissionViewModel,
    viewModel: VaultViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToChats: () -> Unit,
    onNavigateToNoteEditor: (sectionId: String, entryId: String?) -> Unit = { _, _ -> },
) {
    val vaultPreferences = koinInject<VaultPreferences>()
    val localAttachmentStore = koinInject<LocalAttachmentContextStore>()
    val fileSystemHandler = getUriHandler()
    val snackbarHostState = remember { SnackbarHostState() }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val vaultListState = rememberLazyListState()
    var savedScrollIndex by remember { mutableIntStateOf(0) }
    var savedScrollOffset by remember { mutableIntStateOf(0) }

    // Per-section horizontal scroll positions, hoisted ABOVE the AnimatedContent
    // (grid <-> gallery) so they survive the grid being disposed while the
    // full-screen overlay is open. Without this every section's LazyRow resets to
    // 0 on close, so the tile the close hero must fly back to is at the wrong x —
    // or not composed at all (a LazyRow only composes visible items) — and the
    // shared-element transition snaps instead of flying home. Mirrors the
    // vertical vaultListState hoist above; keyed by sectionId. Stale entries for
    // deleted sections are harmless (sections are few).
    val sectionRowStates = remember { mutableMapOf<Uuid, LazyListState>() }

    LaunchedEffect(uiState.fullScreenOverlay) {
        if (uiState.fullScreenOverlay != null) {
            savedScrollIndex = vaultListState.firstVisibleItemIndex
            savedScrollOffset = vaultListState.firstVisibleItemScrollOffset
        } else if (savedScrollIndex > 0 || savedScrollOffset > 0) {
            vaultListState.scrollToItem(savedScrollIndex, savedScrollOffset)
        }
    }

    var pendingError by remember { mutableStateOf<VaultError?>(null) }

    pendingError?.let { error ->
        val message = resolveVaultError(error)
        LaunchedEffect(error) {
            snackbarHostState.showSnackbar(message)
            pendingError = null
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is VaultUiEvent.ShareFileReady -> {
                    fileSystemHandler.shareFile(Path(event.filePath))
                }
                is VaultUiEvent.SaveFileReady -> {
                    fileSystemHandler.saveFile(
                        file = Path(event.filePath),
                        suggestedName = event.fileName,
                    )
                }
                is VaultUiEvent.Error -> {
                    pendingError = event.error
                }
                is VaultUiEvent.OpenNoteEditor -> {
                    onNavigateToNoteEditor(
                        event.sectionId.toString(),
                        event.entryId?.toString(),
                    )
                }
                is VaultUiEvent.Activated,
                is VaultUiEvent.CloseOnboarding -> { /* handled elsewhere */ }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.onAction(VaultUiAction.CloseOverlay) }
    }

    @Suppress("DEPRECATION")
    BackHandler(enabled = uiState.fullScreenOverlay != null) {
        viewModel.onAction(VaultUiAction.CloseOverlay)
    }

    // Section dialog state
    var showNewSectionSheet by remember { mutableStateOf(false) }
    var showImageAddSheet by remember { mutableStateOf(false) }
    var activeSectionForEntry by remember { mutableStateOf<VaultSection?>(null) }
    var sectionToDelete by remember { mutableStateOf<VaultSection?>(null) }
    var sectionToRename by remember { mutableStateOf<VaultSection?>(null) }

    // Picker active tracking (passed to biometric gate to suppress background detection)
    var isPickerActive by remember { mutableStateOf(false) }

    var fileForAppend by remember { mutableStateOf<VaultEntry?>(null) }

    // Camera picker — dispatches to append or add based on fileForAppend
    val cameraLauncher = rememberCameraManager { file ->
        file?.let {
            val appendFile = fileForAppend
            if (appendFile != null) {
                viewModel.onAction(VaultUiAction.AppendPages(appendFile, listOf(it)))
                fileForAppend = null
            } else {
                activeSectionForEntry?.let { section ->
                    viewModel.onAction(VaultUiAction.AddEntryToSection(section.sectionId, listOf(it)))
                }
            }
        }
    }

    // Image picker — dispatches to append or add based on fileForAppend
    val imagePicker = rememberFilePickerLauncher(
        type = FileKitType.Image,
        mode = FileKitMode.Multiple(),
    ) { files ->
        if (!files.isNullOrEmpty()) {
            val appendFile = fileForAppend
            if (appendFile != null) {
                viewModel.onAction(VaultUiAction.AppendPages(appendFile, files))
                fileForAppend = null
            } else {
                activeSectionForEntry?.let { section ->
                    viewModel.onAction(VaultUiAction.AddEntryToSection(section.sectionId, files))
                }
            }
        }
    }

    // File picker — all file types (PDFs get thumbnail + preview automatically)
    val documentPicker = rememberFilePickerLauncher(
        type = FileKitType.File(),
        mode = FileKitMode.Multiple(),
    ) { files ->
        if (!files.isNullOrEmpty()) {
            val appendFile = fileForAppend
            if (appendFile != null) {
                viewModel.onAction(VaultUiAction.AppendPages(appendFile, files))
                fileForAppend = null
            } else {
                activeSectionForEntry?.let { section ->
                    viewModel.onAction(VaultUiAction.AddEntryToSection(section.sectionId, files))
                }
            }
        }
    }

    VaultBiometricGate(
        vaultPreferences = vaultPreferences,
        vaultExtendPermissionViewModel = vaultExtendPermissionViewModel,
        onExpired = onNavigateToChats,
        isPickerActive = isPickerActive,
        onPickerHandled = { isPickerActive = false },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (uiState.fullScreenOverlay == null) {
                    TopAppBar(
                        title = { Text(stringResource(MR.string.vault_label)) },
                        actions = {
                            IconButton(onClick = onNavigateToSettings) {
                                Icon(
                                    imageVector = Icons.Outlined.Settings,
                                    contentDescription = stringResource(MR.string.vault_settings),
                                )
                            }
                        },
                    )
                }
            },
            floatingActionButton = {
                if (uiState.sections.isNotEmpty() && uiState.fullScreenOverlay == null) {
                    VaultAddSectionControl(onAddSection = { showNewSectionSheet = true })
                }
            },
        ) { innerPadding ->
            val contentModifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)

            val transitionDuration =
                HomebaseConstants.Animation.CHAT_IMAGE_FULL_SCREEN_TRANSITION_DURATION

            SharedTransitionLayout {
                // Opaque dark floor beneath the cross-fade, present only while a gallery
                // overlay is the target. The gallery destination is dark (its
                // BottomSheetScaffold paints colorScheme.scrim) but the outer Scaffold's
                // floor is the light colorScheme.background. Without this backdrop, at the
                // cross-fade midpoint both the outgoing grid and incoming dark gallery sit
                // at ~0.5 alpha, so the light Scaffold floor shows through the half-faded
                // gallery = a whole-screen brightness flash. Painting the same scrim
                // beneath the AnimatedContent makes the floor match the gallery, so the
                // fade reveals dark-on-dark with no contrast (mirrors chat's frame-0
                // opaque .background(surface) in FullScreenMediaViewer). Gated on
                // overlay != null so the grid/home appearance is unchanged when closed.
                if (uiState.fullScreenOverlay != null) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim),
                    )
                }
                AnimatedContent(
                    targetState = uiState.fullScreenOverlay,
                    transitionSpec = {
                        fadeIn(tween(transitionDuration)) togetherWith fadeOut(tween(transitionDuration))
                    },
                    contentKey = { it?.let { "gallery" } },
                ) { overlay ->
                    if (overlay == null) {
                        VaultContent(
                            sections = uiState.sections,
                            isLoading = uiState.isLoading,
                            isSyncing = uiState.isSyncing,
                            localAttachmentStore = localAttachmentStore,
                            onEntryClick = {
                                viewModel.onAction(VaultUiAction.EntryClicked(it))
                            },
                            onAddEntry = { section ->
                                activeSectionForEntry = section
                                showImageAddSheet = true
                            },
                            onMoveUp = {
                                viewModel.onAction(VaultUiAction.MoveSectionUp(it))
                            },
                            onMoveDown = {
                                viewModel.onAction(VaultUiAction.MoveSectionDown(it))
                            },
                            onRenameSection = { sectionToRename = it },
                            onDeleteSection = { sectionToDelete = it },
                            onAddSection = { showNewSectionSheet = true },
                            modifier = contentModifier,
                            lazyListState = vaultListState,
                            sectionRowStates = sectionRowStates,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this@AnimatedContent,
                        )
                    } else if (overlay is VaultOverlay.Gallery) {
                        VaultGalleryScreen(
                            file = overlay.file,
                            initialPage = overlay.initialPage,
                            onDismiss = { viewModel.onAction(VaultUiAction.CloseOverlay) },
                            onSharePage = { key ->
                                viewModel.onAction(VaultUiAction.SharePage(overlay.file, key))
                            },
                            onSavePage = { key ->
                                viewModel.onAction(VaultUiAction.SavePage(overlay.file, key))
                            },
                            onDeletePage = { key ->
                                viewModel.onAction(VaultUiAction.DeletePage(overlay.file, key))
                            },
                            onAppendPages = {
                                fileForAppend = overlay.file
                                showImageAddSheet = true
                            },
                            onUpdateLabel = { label ->
                                viewModel.onAction(VaultUiAction.UpdateLabel(overlay.file, label))
                            },
                            onUpdateNotes = { notes ->
                                viewModel.onAction(VaultUiAction.UpdateNotes(overlay.file, notes))
                            },
                            onDeleteEntry = {
                                viewModel.onAction(VaultUiAction.DeleteFile(overlay.file))
                            },
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this@AnimatedContent,
                        )
                    }
                }
            }
        }
    }

    // New Section bottom sheet
    if (showNewSectionSheet) {
        val sheetState = rememberModalBottomSheetState()
        VaultNewSectionSheet(
            sheetState = sheetState,
            existingSectionNames = uiState.sections.map { it.title }.toSet(),
            onAdd = { title ->
                viewModel.onAction(VaultUiAction.AddSection(title))
                showNewSectionSheet = false
            },
            onDismiss = { showNewSectionSheet = false },
        )
    }

    // Deferred picker launch — lets the bottom sheet fully dismiss on iOS before presenting the picker
    var pendingPickerAction by remember { mutableStateOf<VaultPickerAction?>(null) }
    LaunchedEffect(pendingPickerAction) {
        when (pendingPickerAction) {
            VaultPickerAction.Camera -> {
                isPickerActive = true
                cameraLauncher.launch()
            }
            VaultPickerAction.Gallery -> {
                isPickerActive = true
                imagePicker.launch()
            }
            VaultPickerAction.File -> {
                isPickerActive = true
                documentPicker.launch()
            }
            VaultPickerAction.Note -> {
                activeSectionForEntry?.let { section ->
                    onNavigateToNoteEditor(section.sectionId.toString(), null)
                }
            }
            null -> {}
        }
        pendingPickerAction = null
    }

    // Entry add bottom sheet
    if (showImageAddSheet) {
        val sheetState = rememberModalBottomSheetState()
        VaultAddEntrySheet(
            sheetState = sheetState,
            onTakePhoto = {
                showImageAddSheet = false
                pendingPickerAction = VaultPickerAction.Camera
            },
            onChooseGallery = {
                showImageAddSheet = false
                pendingPickerAction = VaultPickerAction.Gallery
            },
            onChooseFile = {
                showImageAddSheet = false
                pendingPickerAction = VaultPickerAction.File
            },
            onAddNote = {
                showImageAddSheet = false
                pendingPickerAction = VaultPickerAction.Note
            },
            onDismiss = {
                showImageAddSheet = false
                activeSectionForEntry = null
                fileForAppend = null
            },
        )
    }

    // Section delete confirmation dialog
    sectionToDelete?.let { section ->
        AlertDialog(
            onDismissRequest = { sectionToDelete = null },
            title = {
                Text(
                    stringResource(MR.string.vault_section_delete_confirm_title, section.title),
                )
            },
            text = {
                Text(stringResource(MR.string.vault_section_delete_confirm_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onAction(VaultUiAction.DeleteSection(section))
                        sectionToDelete = null
                    },
                ) {
                    Text(
                        text = stringResource(MR.string.vault_section_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { sectionToDelete = null }) {
                    Text(stringResource(MR.string.vault_permission_cancel))
                }
            },
        )
    }

    // Section rename dialog
    sectionToRename?.let { section ->
        var nameField by remember(section) {
            mutableStateOf(TextFieldValue(section.title, TextRange(0, section.title.length)))
        }
        val sectionFocus = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { sectionToRename = null },
            title = { Text(stringResource(MR.string.vault_section_rename)) },
            text = {
                OutlinedTextField(
                    value = nameField,
                    onValueChange = { nameField = it },
                    singleLine = true,
                    modifier = Modifier.focusRequester(sectionFocus),
                )
                LaunchedEffect(Unit) { sectionFocus.requestFocus() }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onAction(VaultUiAction.RenameSection(section, nameField.text))
                        sectionToRename = null
                    },
                ) {
                    Text(stringResource(MR.string.vault_rename_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { sectionToRename = null }) {
                    Text(stringResource(MR.string.vault_permission_cancel))
                }
            },
        )
    }

}

private enum class VaultPickerAction { Camera, Gallery, File, Note }

@Composable
private fun resolveVaultError(error: VaultError): String = when (error) {
    VaultError.CreateSectionFailed -> stringResource(MR.string.vault_error_create_section)
    VaultError.RenameSectionFailed -> stringResource(MR.string.vault_error_rename_section)
    VaultError.DeleteSectionFailed -> stringResource(MR.string.vault_error_delete_section)
    is VaultError.UploadFailed -> stringResource(MR.string.vault_error_upload, error.fileName)
    VaultError.DownloadFailed -> stringResource(MR.string.vault_error_download)
    is VaultError.RenameFileFailed -> stringResource(MR.string.vault_error_rename_file, error.fileName)
    is VaultError.DeleteFileFailed -> stringResource(MR.string.vault_error_delete_file, error.fileName)
    VaultError.AppendPagesFailed -> stringResource(MR.string.vault_error_append_pages)
    VaultError.DeletePageFailed -> stringResource(MR.string.vault_error_delete_page)
    VaultError.SaveNotesFailed -> stringResource(MR.string.vault_error_save_notes)
    is VaultError.UpdateLabelFailed -> stringResource(MR.string.vault_error_update_label, error.fileName)
    VaultError.DownloadPageFailed -> stringResource(MR.string.vault_error_download_page)
    VaultError.OutboxUploadFailed -> stringResource(MR.string.vault_error_outbox_upload)
}
