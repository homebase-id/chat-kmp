package id.homebase.core.ui.screens.vault

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.chat.widget.ExtendPermissionDialog
import id.homebase.core.vault.BiometricResult
import id.homebase.core.vault.VaultPreferences
import id.homebase.core.vault.ViewMode
import id.homebase.core.vault.authenticateBiometric
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.vault_biometric_prompt_subtitle
import id.homebase.resources.vault_biometric_prompt_title
import id.homebase.resources.vault_delete_confirm_action
import id.homebase.resources.vault_delete_confirm_message
import id.homebase.resources.vault_delete_confirm_title
import id.homebase.resources.vault_add_file
import id.homebase.resources.vault_label
import id.homebase.resources.vault_permission_cancel
import id.homebase.resources.vault_settings
import id.homebase.resources.vault_toggle_view_mode
import id.homebase.resources.vault_rename_action
import id.homebase.resources.vault_rename_title
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
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

    // Dialog state
    var fileToDelete by remember { mutableStateOf<VaultFileItem?>(null) }
    var fileToRename by remember { mutableStateOf<VaultFileItem?>(null) }

    // File picker
    val filePicker = rememberFilePickerLauncher { file ->
        file?.let { viewModel.onAction(VaultUiAction.FileSelected(it)) }
    }

    Scaffold(
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
                        if (uiState.files.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.onAction(VaultUiAction.ToggleViewMode) }
                            ) {
                                Icon(
                                    imageVector = when (uiState.viewMode) {
                                        ViewMode.List -> Icons.Outlined.GridView
                                        ViewMode.Grid -> Icons.AutoMirrored.Filled.List
                                    },
                                    contentDescription = stringResource(MR.string.vault_toggle_view_mode),
                                )
                            }
                        }
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
            if (authorized && uiState.fullScreenOverlay == null) {
                FloatingActionButton(onClick = { filePicker.launch() }) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(MR.string.vault_add_file)
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            if (authorized) {
                when {
                    uiState.isLoading && uiState.files.isEmpty() -> {
                        CircularProgressIndicator()
                    }

                    uiState.files.isEmpty() -> {
                        VaultEmptyState()
                    }

                    uiState.viewMode == ViewMode.List -> {
                        VaultFileListContent(
                            files = uiState.files,
                            onFileClick = { viewModel.onAction(VaultUiAction.FileClicked(it)) },
                            onRename = { fileToRename = it },
                            onShare = { viewModel.onAction(VaultUiAction.ShareFile(it)) },
                            onDelete = { fileToDelete = it },
                            onRetry = { viewModel.onAction(VaultUiAction.RefreshFiles) },
                        )
                    }

                    uiState.viewMode == ViewMode.Grid -> {
                        VaultFileGridContent(
                            files = uiState.files,
                            onFileClick = { viewModel.onAction(VaultUiAction.FileClicked(it)) },
                            onRename = { fileToRename = it },
                            onShare = { viewModel.onAction(VaultUiAction.ShareFile(it)) },
                            onDelete = { fileToDelete = it },
                            onRetry = { viewModel.onAction(VaultUiAction.RefreshFiles) },
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    fileToDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text(stringResource(MR.string.vault_delete_confirm_title)) },
            text = {
                Text(stringResource(MR.string.vault_delete_confirm_message, file.fileName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onAction(VaultUiAction.DeleteFile(file))
                        fileToDelete = null
                    },
                ) {
                    Text(
                        text = stringResource(MR.string.vault_delete_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) {
                    Text(stringResource(MR.string.vault_permission_cancel))
                }
            },
        )
    }

    // Rename dialog
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

    // Preview overlay (outside Scaffold, on top of everything)
    AnimatedVisibility(
        visible = uiState.fullScreenOverlay != null,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val overlay = uiState.fullScreenOverlay
        if (overlay is VaultOverlay.Preview) {
            VaultPreviewOverlay(
                file = overlay.file,
                onDismiss = { viewModel.onAction(VaultUiAction.CloseOverlay) },
                onShare = { viewModel.onAction(VaultUiAction.ShareFile(overlay.file)) },
                onRename = { fileToRename = overlay.file },
                onDelete = { fileToDelete = overlay.file },
            )
        }
    }
}
