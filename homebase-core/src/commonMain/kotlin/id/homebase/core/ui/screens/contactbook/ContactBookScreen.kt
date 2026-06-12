package id.homebase.core.ui.screens.contactbook

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PersonAddAlt1
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.contactbook.ContactBookPreferences
import id.homebase.core.permissions.PermissionStatus
import id.homebase.core.permissions.PermissionType
import id.homebase.core.permissions.createPermissionsManager
import id.homebase.core.ui.screens.contactbook.components.ContactDetailSheet
import id.homebase.core.ui.screens.contactbook.components.ContactEditSheet
import id.homebase.core.ui.screens.contactbook.deviceimport.ContactImportSheet
import id.homebase.core.vault.BiometricResult
import id.homebase.core.vault.authenticateBiometric
import id.homebase.resources.MR
import id.homebase.resources.contactbook_action_add
import id.homebase.resources.contactbook_action_import
import id.homebase.resources.contactbook_action_settings
import id.homebase.resources.contactbook_error_delete
import id.homebase.resources.contactbook_error_import
import id.homebase.resources.contactbook_error_save
import id.homebase.resources.contactbook_label
import id.homebase.resources.contactbook_locked_unlock
import id.homebase.resources.contactbook_search_hint
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactBookScreen(
    viewModel: ContactBookViewModel,
    onNavigateToSettings: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val preferences = koinInject<ContactBookPreferences>()
    val snackbarHostState = remember { SnackbarHostState() }

    // Optional biometric gate (off by default — opt-in via Settings).
    val promptTitle = stringResource(MR.string.contactbook_label)
    var authorized by remember {
        mutableStateOf(!preferences.biometricsEnabled.value || preferences.isAuthSessionValid())
    }
    var unlockAttempt by remember { mutableStateOf(0) }
    var isAuthenticating by remember { mutableStateOf(false) }

    LaunchedEffect(authorized, unlockAttempt) {
        if (authorized || isAuthenticating) return@LaunchedEffect
        isAuthenticating = true
        when (authenticateBiometric(promptTitle, promptTitle)) {
            BiometricResult.Success, BiometricResult.Unavailable -> {
                preferences.recordAuthSuccess()
                authorized = true
            }
            BiometricResult.Failure -> { /* stay locked */ }
        }
        isAuthenticating = false
    }

    val permissionManager = createPermissionsManager { type, status, _ ->
        if (type == PermissionType.CONTACTS) {
            viewModel.onAction(
                ContactBookUiAction.ImportPermissionResult(status == PermissionStatus.GRANTED)
            )
        }
    }

    // Pre-resolve error strings (cannot call stringResource inside collect).
    val errSave = stringResource(MR.string.contactbook_error_save)
    val errDelete = stringResource(MR.string.contactbook_error_delete)
    val errImport = stringResource(MR.string.contactbook_error_import)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ContactBookUiEvent.RequestContactsPermission ->
                    permissionManager.askPermission(PermissionType.CONTACTS)
                is ContactBookUiEvent.OpenChat -> { /* navigation handled in AppNavHost */ }
                is ContactBookUiEvent.Error -> {
                    val msg = when (event.error) {
                        ContactBookError.SaveFailed -> errSave
                        ContactBookError.DeleteFailed -> errDelete
                        ContactBookError.ImportFailed,
                        ContactBookError.PermissionDenied -> errImport
                    }
                    snackbarHostState.showSnackbar(msg)
                }
                ContactBookUiEvent.CloseOnboarding -> { /* handled in AppNavHost */ }
            }
        }
    }

    if (!authorized) {
        ContactBookLocked(onUnlock = { unlockAttempt++ })
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.contactbook_label)) },
                actions = {
                    if (uiState.importSupported) {
                        IconButton(onClick = { viewModel.onAction(ContactBookUiAction.ImportClicked) }) {
                            Icon(
                                Icons.Outlined.PersonAddAlt1,
                                contentDescription = stringResource(MR.string.contactbook_action_import),
                            )
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(MR.string.contactbook_action_settings),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.onAction(ContactBookUiAction.AddClicked) }) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(MR.string.contactbook_action_add),
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding),
        ) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.onAction(ContactBookUiAction.SearchChanged(it)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text(stringResource(MR.string.contactbook_search_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ContactBookContent(
                uiState = uiState,
                onAction = viewModel::onAction,
                modifier = Modifier.weight(1f),
            )
        }
    }

    when (val overlay = uiState.overlay) {
        is ContactBookOverlay.Detail -> ContactDetailSheet(
            entry = overlay.entry,
            onAction = viewModel::onAction,
            onDismiss = { viewModel.onAction(ContactBookUiAction.CloseOverlay) },
        )
        is ContactBookOverlay.Edit -> ContactEditSheet(
            editing = overlay.entry,
            onAction = viewModel::onAction,
            onDismiss = { viewModel.onAction(ContactBookUiAction.CloseOverlay) },
        )
        null -> {}
    }

    uiState.importState?.let { importState ->
        ContactImportSheet(state = importState, onAction = viewModel::onAction)
    }
}

@Composable
private fun ContactBookLocked(onUnlock: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.material3.Button(onClick = onUnlock) {
            Text(stringResource(MR.string.contactbook_locked_unlock))
        }
    }
}
