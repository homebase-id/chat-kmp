package id.homebase.core.ui.screens.vault

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.chat.widget.ExtendPermissionDialog
import id.homebase.core.ui.screens.vault.model.VaultSectionUiModel
import id.homebase.core.util.getUriHandler
import id.homebase.core.vault.BiometricResult
import id.homebase.core.vault.VaultPreferences
import id.homebase.core.vault.authenticateBiometric
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.vault_biometric_prompt_subtitle
import id.homebase.resources.vault_biometric_prompt_title
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
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun VaultScreen(
    vaultExtendPermissionViewModel: ExtendPermissionViewModel,
    viewModel: VaultViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    val vaultPreferences = koinInject<VaultPreferences>()
    val fileSystemHandler = getUriHandler()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val biometricTitle = stringResource(MR.string.vault_biometric_prompt_title)
    val biometricSubtitle = stringResource(MR.string.vault_biometric_prompt_subtitle)

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var authorized by remember { mutableStateOf(!vaultPreferences.biometricsEnabled.value) }

    // Biometric auth gate
    LaunchedEffect(Unit) {
        if (authorized) return@LaunchedEffect
        when (authenticateBiometric(biometricTitle, biometricSubtitle)) {
            BiometricResult.Success, BiometricResult.Unavailable -> authorized = true
            BiometricResult.Failure -> onNavigateBack()
        }
    }

    // Collect one-time events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is VaultUiEvent.ShareFileReady -> {
                    fileSystemHandler.shareFile(Path(event.filePath))
                }
                is VaultUiEvent.Error -> {
                    scope.launch { snackbarHostState.showSnackbar(event.message) }
                }
                is VaultUiEvent.Activated,
                is VaultUiEvent.CloseOnboarding -> { /* handled elsewhere */ }
            }
        }
    }

    // ON_RESUME lifecycle observer for permission recheck
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vaultExtendPermissionViewModel.recheckPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ExtendPermissionDialog(viewModel = vaultExtendPermissionViewModel)

    @Suppress("DEPRECATION")
    BackHandler(enabled = uiState.fullScreenOverlay != null) {
        viewModel.onAction(VaultUiAction.CloseOverlay)
    }

    // Section dialog state
    var showNewSectionSheet by remember { mutableStateOf(false) }
    var showImageAddSheet by remember { mutableStateOf(false) }
    var activeSectionForEntry by remember { mutableStateOf<VaultSectionUiModel?>(null) }
    var sectionToDelete by remember { mutableStateOf<VaultSectionUiModel?>(null) }
    var sectionToRename by remember { mutableStateOf<VaultSectionUiModel?>(null) }
    var fileToRename by remember { mutableStateOf<VaultFileItem?>(null) }

    // File picker wired to active section
    val filePicker = rememberFilePickerLauncher(
        type = FileKitType.Image,
        mode = FileKitMode.Multiple(),
    ) { files ->
        if (!files.isNullOrEmpty()) {
            activeSectionForEntry?.let { section ->
                viewModel.onAction(VaultUiAction.AddEntryToSection(section.sectionId, files))
            }
        }
    }

    var fileForAppend by remember { mutableStateOf<VaultFileItem?>(null) }

    val appendPicker = rememberFilePickerLauncher(
        type = FileKitType.Image,
        mode = FileKitMode.Multiple(),
    ) { files ->
        if (!files.isNullOrEmpty()) {
            fileForAppend?.let { f ->
                viewModel.onAction(VaultUiAction.AppendPages(f, files))
            }
        }
        fileForAppend = null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (uiState.fullScreenOverlay == null) {
                TopAppBar(
                    title = { Text(stringResource(MR.string.vault_label)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(MR.string.menu_back),
                            )
                        }
                    },
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
            if (authorized && uiState.sections.isNotEmpty() && uiState.fullScreenOverlay == null) {
                VaultAddSectionControl(onAddSection = { showNewSectionSheet = true })
            }
        },
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .consumeWindowInsets(innerPadding)
            .padding(innerPadding)

        if (authorized) {
            when {
                uiState.isLoading && uiState.sections.isEmpty() -> {
                    Box(
                        modifier = contentModifier,
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                uiState.sections.isEmpty() -> {
                    VaultEmptyState(
                        onAddSection = { showNewSectionSheet = true },
                        modifier = contentModifier,
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = contentModifier,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(uiState.sections, key = { it.sectionId }) { section ->
                            VaultSectionCard(
                                section = section,
                                onEntryClick = {
                                    viewModel.onAction(VaultUiAction.EntryClicked(it))
                                },
                                onAddEntry = {
                                    activeSectionForEntry = section
                                    showImageAddSheet = true
                                },
                                onMoveUp = {
                                    viewModel.onAction(VaultUiAction.MoveSectionUp(section))
                                },
                                onMoveDown = {
                                    viewModel.onAction(VaultUiAction.MoveSectionDown(section))
                                },
                                onRenameSection = { sectionToRename = section },
                                onDeleteSection = { sectionToDelete = section },
                            )
                        }
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

    // Image add bottom sheet
    if (showImageAddSheet) {
        val sheetState = rememberModalBottomSheetState()
        VaultImageAddSheet(
            sheetState = sheetState,
            onTakePhoto = { showImageAddSheet = false },
            onChooseGallery = {
                showImageAddSheet = false
                filePicker.launch()
            },
            onDismiss = {
                showImageAddSheet = false
                activeSectionForEntry = null
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
        var newName by remember(section) { mutableStateOf(section.title) }
        AlertDialog(
            onDismissRequest = { sectionToRename = null },
            title = { Text(stringResource(MR.string.vault_section_rename)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onAction(VaultUiAction.RenameSection(section, newName))
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

    // File rename dialog
    fileToRename?.let { file ->
        var newName by remember(file) { mutableStateOf(file.fileName) }
        AlertDialog(
            onDismissRequest = { fileToRename = null },
            title = { Text(stringResource(MR.string.vault_rename_title)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onAction(VaultUiAction.RenameFile(file, newName))
                        fileToRename = null
                    },
                ) {
                    Text(stringResource(MR.string.vault_rename_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToRename = null }) {
                    Text(stringResource(MR.string.vault_permission_cancel))
                }
            },
        )
    }

    // Gallery overlay (outside Scaffold, on top of everything)
    AnimatedVisibility(
        visible = uiState.fullScreenOverlay != null,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val overlay = uiState.fullScreenOverlay
        if (overlay is VaultOverlay.Gallery) {
            VaultGalleryOverlay(
                file = overlay.file,
                initialPage = overlay.initialPage,
                onDismiss = { viewModel.onAction(VaultUiAction.CloseOverlay) },
                onSharePage = { key ->
                    viewModel.onAction(VaultUiAction.SharePage(overlay.file, key))
                },
                onDeletePage = { key ->
                    viewModel.onAction(VaultUiAction.DeletePage(overlay.file, key))
                },
                onAppendPages = {
                    fileForAppend = overlay.file
                    appendPicker.launch()
                },
                onUpdateNotes = { notes ->
                    viewModel.onAction(VaultUiAction.UpdateNotes(overlay.file, notes))
                },
                onDeleteEntry = {
                    viewModel.onAction(VaultUiAction.DeleteFile(overlay.file))
                },
                onRenameEntry = { fileToRename = overlay.file },
            )
        }
    }
}
