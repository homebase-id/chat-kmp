package id.homebase.core.ui.screens.contactbook

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.auth.initials
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.OwnerAvatar
import id.homebase.core.connections.ConnectRequestBottomSheet
import id.homebase.core.connections.ConnectRequestViewModel
import id.homebase.core.ui.screens.contactbook.components.CircleMembersSheet
import id.homebase.core.ui.screens.contactbook.components.ContactEditSheet
import id.homebase.resources.MR
import id.homebase.resources.contactbook_action_add
import id.homebase.resources.contactbook_error_delete
import id.homebase.resources.contactbook_error_forbidden
import id.homebase.resources.contactbook_error_clear_unsupported
import id.homebase.resources.contactbook_error_message
import id.homebase.resources.contactbook_error_photo
import id.homebase.resources.contactbook_error_save
import id.homebase.resources.contactbook_label
import id.homebase.resources.contactbook_search_hint
import id.homebase.resources.contactbook_tab_circles
import id.homebase.resources.contactbook_tab_contacts
import id.homebase.resources.clear_input
import id.homebase.resources.menu_back
import id.homebase.resources.search
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ContactBookScreen(
    viewModel: ContactBookViewModel,
    connectRequestViewModel: ConnectRequestViewModel,
    onProfileClick: () -> Unit,
    onOpenConversation: (Uuid) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Pre-resolve error strings (cannot call stringResource inside collect).
    val errSave = stringResource(MR.string.contactbook_error_save)
    val errDelete = stringResource(MR.string.contactbook_error_delete)
    val errPhoto = stringResource(MR.string.contactbook_error_photo)
    val errMessage = stringResource(MR.string.contactbook_error_message)
    val errClearUnsupported = stringResource(MR.string.contactbook_error_clear_unsupported)
    val errForbidden = stringResource(MR.string.contactbook_error_forbidden)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ContactBookUiEvent.OpenConversation -> { /* navigation handled in AppNavHost */ }
                is ContactBookUiEvent.OpenDetail -> { /* navigation handled in AppNavHost */ }
                ContactBookUiEvent.OpenAddContact -> { /* navigation handled in AppNavHost */ }
                is ContactBookUiEvent.Error -> {
                    val msg = when (event.error) {
                        ContactBookError.SaveFailed -> errSave
                        ContactBookError.SaveForbidden -> errForbidden
                        ContactBookError.DeleteFailed -> errDelete
                        ContactBookError.PhotoFailed -> errPhoto
                        ContactBookError.MessageFailed -> errMessage
                        ContactBookError.ClearUnsupported -> errClearUnsupported
                    }
                    snackbarHostState.showSnackbar(msg)
                }
                ContactBookUiEvent.CloseOnboarding -> { /* handled in AppNavHost */ }
            }
        }
    }

    val onContacts = uiState.selectedTab == ContactTab.CONTACTS

    // Search is hidden behind a top-bar icon; it expands into the app-bar title when tapped
    // and collapses (clearing the query) on back/close. Mirrors the conversation-list pattern.
    var searchActive by rememberSaveable { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }

    val collapseSearch = {
        searchActive = false
        viewModel.onAction(ContactBookUiAction.SearchChanged(""))
    }

    LaunchedEffect(searchActive) {
        if (searchActive) searchFocusRequester.requestFocus()
    }
    @Suppress("DEPRECATION") BackHandler(enabled = searchActive) { collapseSearch() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (searchActive) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = collapseSearch) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(MR.string.menu_back),
                            )
                        }
                    },
                    title = {
                        TextField(
                            value = uiState.searchQuery,
                            onValueChange = { viewModel.onAction(ContactBookUiAction.SearchChanged(it)) },
                            placeholder = { Text(stringResource(MR.string.contactbook_search_hint)) },
                            singleLine = true,
                            trailingIcon = {
                                if (uiState.searchQuery.isNotEmpty()) {
                                    IconButton(onClick = {
                                        viewModel.onAction(ContactBookUiAction.SearchChanged(""))
                                    }) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = stringResource(MR.string.clear_input),
                                        )
                                    }
                                }
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester),
                        )
                    },
                )
            } else {
                TopAppBar(
                    // Owner avatar on the left links to settings — mirrors the Moments header.
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Spacer(modifier = Modifier.width(4.dp))
                            AnimatedVisibility(
                                visible = uiState.ownerSession != null,
                                enter = fadeIn(animationSpec = tween(300, delayMillis = 200)),
                                exit = fadeOut(animationSpec = tween(150)),
                            ) {
                                uiState.ownerSession?.let { session ->
                                    OwnerAvatar(
                                        odinId = session.odinId,
                                        profileImageData = null,
                                        initials = session.initials(),
                                        connectionStatus = uiState.connectionStatus,
                                        driveIsSyncing = uiState.driveIsSyncing,
                                        hasDriveError = uiState.hasDriveError,
                                        options = AvatarOptions(
                                            size = 32.dp,
                                            fontSize = 12.sp,
                                            onClick = onProfileClick,
                                        ),
                                        animatedVisibilityScope = this@AnimatedVisibility,
                                        sharedTransitionScope = null,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(stringResource(MR.string.contactbook_label))
                        }
                    },
                    actions = {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = stringResource(MR.string.search),
                            )
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            // Composing a brand-new connection request to any Homebase ID doesn't need its own
            // FAB entry point — Add Contact already offers "Send connection request" once an
            // entered ID resolves to someone not yet connected.
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
            // The search query (driven from the top-bar search field) filters both the
            // Contacts and Circles tabs.
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
                ContactTab.CONTACTS -> ContactBookContent(
                    uiState = uiState,
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
        is ContactBookOverlay.Edit -> ContactEditSheet(
            editing = overlay.entry,
            onSave = { draft, addPhones, addEmails, photo ->
                viewModel.onAction(
                    ContactBookUiAction.SaveContact(draft, overlay.entry, addPhones, addEmails, photo),
                )
            },
            onDismiss = { viewModel.onAction(ContactBookUiAction.CloseOverlay) },
            odinIdLocked = overlay.entry?.odinId?.lowercase() in uiState.connectedOdinIds,
        )
        null -> {}
    }

    uiState.circleMembers?.let { members ->
        CircleMembersSheet(state = members, onAction = viewModel::onAction)
    }

    // Compose-a-new-request sheet, opened by the FAB while the Requests pill is active.
    ConnectRequestBottomSheet(
        viewModel = connectRequestViewModel,
        snackbarHostState = snackbarHostState,
        onNavigateToConversation = onOpenConversation,
    )
}

private fun ContactTab.labelRes() = when (this) {
    ContactTab.CONTACTS -> MR.string.contactbook_tab_contacts
    ContactTab.CIRCLES -> MR.string.contactbook_tab_circles
}
