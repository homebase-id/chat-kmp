package id.homebase.core.ui.screens.contactbook

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
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.permissions.PermissionStatus
import id.homebase.core.permissions.PermissionType
import id.homebase.core.permissions.createPermissionsManager
import id.homebase.core.ui.screens.contactbook.components.CircleMembersSheet
import id.homebase.core.ui.screens.contactbook.components.ContactDetailSheet
import id.homebase.core.ui.screens.contactbook.components.ContactEditSheet
import id.homebase.core.ui.screens.contactbook.deviceimport.ContactImportSheet
import id.homebase.resources.MR
import id.homebase.resources.contactbook_action_add
import id.homebase.resources.contactbook_action_import
import id.homebase.resources.contactbook_action_settings
import id.homebase.resources.contactbook_error_delete
import id.homebase.resources.contactbook_error_import
import id.homebase.resources.contactbook_error_message
import id.homebase.resources.contactbook_error_photo
import id.homebase.resources.contactbook_error_save
import id.homebase.resources.contactbook_label
import id.homebase.resources.contactbook_search_hint
import id.homebase.resources.contactbook_tab_circles
import id.homebase.resources.contactbook_tab_connections
import id.homebase.resources.contactbook_tab_contacts
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactBookScreen(
    viewModel: ContactBookViewModel,
    onNavigateToSettings: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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
    val errPhoto = stringResource(MR.string.contactbook_error_photo)
    val errMessage = stringResource(MR.string.contactbook_error_message)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ContactBookUiEvent.RequestContactsPermission ->
                    permissionManager.askPermission(PermissionType.CONTACTS)
                is ContactBookUiEvent.OpenConversation -> { /* navigation handled in AppNavHost */ }
                is ContactBookUiEvent.Error -> {
                    val msg = when (event.error) {
                        ContactBookError.SaveFailed -> errSave
                        ContactBookError.DeleteFailed -> errDelete
                        ContactBookError.PhotoFailed -> errPhoto
                        ContactBookError.MessageFailed -> errMessage
                        ContactBookError.ImportFailed,
                        ContactBookError.PermissionDenied -> errImport
                    }
                    snackbarHostState.showSnackbar(msg)
                }
                ContactBookUiEvent.CloseOnboarding -> { /* handled in AppNavHost */ }
            }
        }
    }

    val onContacts = uiState.selectedTab == ContactTab.CONTACTS

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.contactbook_label)) },
                actions = {
                    if (onContacts && uiState.importSupported) {
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
            if (onContacts) {
                FloatingActionButton(onClick = { viewModel.onAction(ContactBookUiAction.AddClicked) }) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(MR.string.contactbook_action_add),
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding),
        ) {
            PrimaryTabRow(selectedTabIndex = uiState.selectedTab.ordinal) {
                ContactTab.entries.forEach { tab ->
                    Tab(
                        selected = uiState.selectedTab == tab,
                        onClick = { viewModel.onAction(ContactBookUiAction.TabSelected(tab)) },
                        text = { Text(stringResource(tab.labelRes())) },
                    )
                }
            }

            when (uiState.selectedTab) {
                ContactTab.CONTACTS -> {
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
                ContactTab.CONNECTIONS -> ConnectionsTabContent(
                    connections = uiState.connections,
                    onAction = viewModel::onAction,
                    modifier = Modifier.weight(1f),
                )
                ContactTab.CIRCLES -> CirclesTabContent(
                    circles = uiState.circles,
                    loading = uiState.circlesLoading,
                    onAction = viewModel::onAction,
                    modifier = Modifier.weight(1f),
                )
            }
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

    uiState.circleMembers?.let { members ->
        CircleMembersSheet(state = members, onAction = viewModel::onAction)
    }
}

private fun ContactTab.labelRes() = when (this) {
    ContactTab.CONTACTS -> MR.string.contactbook_tab_contacts
    ContactTab.CONNECTIONS -> MR.string.contactbook_tab_connections
    ContactTab.CIRCLES -> MR.string.contactbook_tab_circles
}
