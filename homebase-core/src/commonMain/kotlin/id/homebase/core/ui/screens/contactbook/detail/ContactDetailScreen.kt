@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)

package id.homebase.core.ui.screens.contactbook.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContactEmergency
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PersonAddAlt1
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.common.OdinId
import id.homebase.chat.widget.AvatarFullScreenViewer
import id.homebase.chat.widget.ChatMediaFullScreenHost
import id.homebase.core.HomebaseConstants
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.connections.ConnectRequestAction
import id.homebase.core.media.subsample.SubSamplingImageSource
import id.homebase.core.connections.ConnectRequestBottomSheet
import id.homebase.core.ui.screens.contactbook.components.CircleMembersSheet
import id.homebase.core.connections.ConnectRequestViewModel
import id.homebase.core.ui.screens.contactbook.RequestDirection
import id.homebase.core.ui.screens.contactbook.components.ContactBookAvatar
import id.homebase.core.ui.screens.contactbook.components.ContactEditSheet
import id.homebase.core.util.formatTimestamp
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.contactbook_action_blocked
import id.homebase.resources.contactbook_action_request_accepted
import id.homebase.resources.contactbook_action_request_cancelled
import id.homebase.resources.contactbook_action_request_rejected
import id.homebase.resources.contactbook_action_request_withdrawn
import id.homebase.resources.contactbook_action_sync_started
import id.homebase.resources.contactbook_action_disconnected
import id.homebase.resources.contactbook_detail_emergency_badge
import id.homebase.resources.contactbook_detail_location_data_as_of
import id.homebase.resources.contactbook_action_unblocked
import id.homebase.resources.contactbook_connected
import id.homebase.resources.contactbook_detail_about_empty
import id.homebase.resources.contactbook_detail_activity_empty
import id.homebase.resources.contactbook_detail_block
import id.homebase.resources.contactbook_detail_block_message
import id.homebase.resources.contactbook_detail_block_title
import id.homebase.resources.contactbook_detail_blocked
import id.homebase.resources.contactbook_detail_connect
import id.homebase.resources.contactbook_detail_delete
import id.homebase.resources.contactbook_detail_delete_message
import id.homebase.resources.contactbook_detail_delete_message_connected
import id.homebase.resources.contactbook_detail_delete_title
import id.homebase.resources.contactbook_detail_disconnect
import id.homebase.resources.contactbook_detail_disconnect_message
import id.homebase.resources.contactbook_detail_disconnect_title
import id.homebase.resources.contactbook_detail_edit
import id.homebase.resources.contactbook_detail_manage
import id.homebase.resources.contactbook_detail_message
import id.homebase.resources.contactbook_detail_sync
import id.homebase.resources.contactbook_detail_tab_about
import id.homebase.resources.contactbook_detail_tab_activity
import id.homebase.resources.contactbook_detail_tab_details
import id.homebase.resources.contactbook_detail_unblock
import id.homebase.resources.contactbook_detail_cancel_request
import id.homebase.resources.contactbook_detail_not_connected
import id.homebase.resources.contactbook_detail_pending
import id.homebase.resources.contactbook_detail_request_outgoing
import id.homebase.resources.contactbook_error_connection_forbidden
import id.homebase.resources.contactbook_error_delete
import id.homebase.resources.contactbook_error_delete_forbidden
import id.homebase.resources.contactbook_error_forbidden
import id.homebase.resources.contactbook_error_clear_unsupported
import id.homebase.resources.contactbook_error_photo
import id.homebase.resources.contactbook_error_save
import id.homebase.resources.menu_back
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Composable
fun ContactDetailScreen(
    viewModel: ContactDetailViewModel,
    connectRequestViewModel: ConnectRequestViewModel,
    onBack: () -> Unit,
    // Separate from onBack: fired only when the contact was actually deleted, so the
    // contact book can clear a search whose only match may just have disappeared (#876).
    onDeleted: () -> Unit = onBack,
    onOpenConversation: (Uuid) -> Unit,
    onSeeAllMedia: (conversationId: String) -> Unit,
    onOpenContact: (uniqueId: String, odinId: String?) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val errSave = stringResource(MR.string.contactbook_error_save)
    val errPhoto = stringResource(MR.string.contactbook_error_photo)
    val errClearUnsupported = stringResource(MR.string.contactbook_error_clear_unsupported)
    val errForbidden = stringResource(MR.string.contactbook_error_forbidden)
    val errDelete = stringResource(MR.string.contactbook_error_delete)
    val errDeleteForbidden = stringResource(MR.string.contactbook_error_delete_forbidden)
    val errConnectionForbidden = stringResource(MR.string.contactbook_error_connection_forbidden)
    val msgBlocked = stringResource(MR.string.contactbook_action_blocked)
    val msgUnblocked = stringResource(MR.string.contactbook_action_unblocked)
    val msgDisconnected = stringResource(MR.string.contactbook_action_disconnected)
    val msgSyncStarted = stringResource(MR.string.contactbook_action_sync_started)
    val msgRequestAccepted = stringResource(MR.string.contactbook_action_request_accepted)
    val msgRequestRejected = stringResource(MR.string.contactbook_action_request_rejected)
    val msgRequestCancelled = stringResource(MR.string.contactbook_action_request_cancelled)
    val msgRequestWithdrawn = stringResource(MR.string.contactbook_action_request_withdrawn)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ContactDetailEvent.OpenConversation -> onOpenConversation(event.conversationId)
                is ContactDetailEvent.SeeAllMedia -> onSeeAllMedia(event.conversationId)
                ContactDetailEvent.Back -> onBack()
                ContactDetailEvent.DeletedAndBack -> onDeleted()
                ContactDetailEvent.Error -> snackbarHostState.showSnackbar(errSave)
                ContactDetailEvent.Forbidden -> snackbarHostState.showSnackbar(errForbidden)
                ContactDetailEvent.DeleteError -> snackbarHostState.showSnackbar(errDelete)
                ContactDetailEvent.DeleteForbidden ->
                    snackbarHostState.showSnackbar(errDeleteForbidden)
                ContactDetailEvent.ConnectionForbidden ->
                    snackbarHostState.showSnackbar(errConnectionForbidden)
                ContactDetailEvent.PhotoError -> snackbarHostState.showSnackbar(errPhoto)
                ContactDetailEvent.ClearUnsupported ->
                    snackbarHostState.showSnackbar(errClearUnsupported)
                ContactDetailEvent.Blocked -> snackbarHostState.showSnackbar(msgBlocked)
                ContactDetailEvent.Unblocked -> snackbarHostState.showSnackbar(msgUnblocked)
                ContactDetailEvent.Disconnected -> snackbarHostState.showSnackbar(msgDisconnected)
                ContactDetailEvent.SyncStarted -> snackbarHostState.showSnackbar(msgSyncStarted)
                ContactDetailEvent.RequestAccepted -> snackbarHostState.showSnackbar(msgRequestAccepted)
                ContactDetailEvent.RequestRejected -> snackbarHostState.showSnackbar(msgRequestRejected)
                ContactDetailEvent.RequestCancelled -> snackbarHostState.showSnackbar(msgRequestCancelled)
                ContactDetailEvent.RequestWithdrawn -> snackbarHostState.showSnackbar(msgRequestWithdrawn)
                is ContactDetailEvent.OpenOtherContact -> onOpenContact(event.uniqueId, event.odinId)
            }
        }
    }

    // Returning here (e.g. from another contact's detail opened via the circle-detail dialog)
    // needs to re-check pending circles explicitly — same StateFlow-conflation gap as the
    // Contact Book's circle sheet: a pending-only change doesn't alter real membership, so
    // ConnectionService.circles never re-emits and the reactive path alone can't catch it (#1096).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPendingCircles()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The contact's photo opened full-screen. Kept out of [uiState.fullScreenMedia]:
    // that one is a chat attachment, this is a profile image. Null = closed.
    var fullScreenAvatar by remember { mutableStateOf<SubSamplingImageSource?>(null) }

    // Hoisted above the AnimatedContent below, which disposes the screen branch while the
    // viewer is open — neither rememberSaveable nor remember survives that dispose.
    val entryId = uiState.entry?.uniqueId
    var detailsExpanded by rememberSaveable(entryId) { mutableStateOf(false) }
    var selectedTab by rememberSaveable(entryId) { mutableStateOf(ContactDetailTab.DETAILS) }
    val currentTab = if (selectedTab in contactDetailTabs) selectedTab else ContactDetailTab.DETAILS
    // Fresh scroll position per tab.
    val tabScroll = remember(currentTab) { ScrollState(0) }

    // The viewer replaces the screen rather than drawing inside the Scaffold's content slot: it
    // brings its own top bar, which would otherwise take the status-bar inset a second time.
    // Sharing one transition layout with the screen is also what lets the avatar morph into it.
    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = fullScreenAvatar,
            contentKey = { it == null },
            transitionSpec = {
                fadeIn(tween(HomebaseConstants.Animation.CHAT_IMAGE_FULL_SCREEN_TRANSITION_DURATION)) togetherWith
                    fadeOut(tween(HomebaseConstants.Animation.CHAT_IMAGE_FULL_SCREEN_TRANSITION_DURATION))
            },
            label = "contactAvatarViewer",
        ) { avatar ->
            if (avatar == null) {
                ContactDetailContent(
                    uiState = uiState,
                    snackbarHostState = snackbarHostState,
                    onAction = viewModel::onAction,
                    onAvatarClick = { fullScreenAvatar = it },
                    onConnect = {
                        uiState.entry?.odinId?.let { domain ->
                            runCatching { OdinId(domain) }.getOrNull()?.let {
                                connectRequestViewModel.onAction(
                                    ConnectRequestAction.OpenDialogWithRecipient(it)
                                )
                            }
                        }
                    },
                    currentTab = currentTab,
                    onSelectTab = { selectedTab = it },
                    detailsExpanded = detailsExpanded,
                    onToggleDetails = { detailsExpanded = !detailsExpanded },
                    tabScroll = tabScroll,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedContent,
                )
            } else {
                AvatarFullScreenViewer(
                    source = avatar,
                    title = uiState.entry?.displayName.orEmpty(),
                    onDismiss = { fullScreenAvatar = null },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedContent,
                )
            }
        }
    }

    if (uiState.editOpen) {
        ContactEditSheet(
            editing = uiState.entry,
            onSave = { draft, addPhones, addEmails, photo ->
                viewModel.onAction(
                    ContactDetailAction.SaveContact(draft, addPhones, addEmails, photo),
                )
            },
            onDismiss = { viewModel.onAction(ContactDetailAction.CloseEdit) },
            odinIdLocked = uiState.isConnected,
        )
    }

    uiState.circleDetail?.let { detail ->
        CircleMembersSheet(
            state = detail,
            onDismiss = { viewModel.onAction(ContactDetailAction.CircleDetailDismiss) },
            onMemberClick = { viewModel.onAction(ContactDetailAction.CircleMemberClicked(it)) },
            onAddMemberClick = {},
            onRemoveMemberClick = {},
        )
    }

    // Connection-request dialog (sheet), opened by the "Send connection request" button.
    ConnectRequestBottomSheet(
        viewModel = connectRequestViewModel,
        snackbarHostState = snackbarHostState,
        onNavigateToConversation = onOpenConversation,
    )

    uiState.confirm?.let { confirm ->
        ConfirmDialog(
            confirm = confirm,
            isConnected = uiState.isConnected,
            onConfirm = { viewModel.onAction(ContactDetailAction.ConfirmYes) },
            onDismiss = { viewModel.onAction(ContactDetailAction.ConfirmDismiss) },
        )
    }
}

@Composable
private fun ContactDetailContent(
    uiState: ContactDetailUiState,
    snackbarHostState: SnackbarHostState,
    onAction: (ContactDetailAction) -> Unit,
    onAvatarClick: (SubSamplingImageSource) -> Unit,
    onConnect: () -> Unit,
    currentTab: ContactDetailTab,
    onSelectTab: (ContactDetailTab) -> Unit,
    detailsExpanded: Boolean,
    onToggleDetails: () -> Unit,
    tabScroll: ScrollState,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    Scaffold(
        topBar = {
            // While the full-screen media viewer is open it draws its own top bar
            // (contact name + date + back/menu). Suppress this screen's app bar so
            // the two don't stack — the viewer's opaque surface already covers the
            // content beneath it. Mirrors ConversationMediaScreen.
            if (uiState.fullScreenMedia == null) {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = { onAction(ContactDetailAction.BackClicked) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(MR.string.menu_back),
                            )
                        }
                    },
                    actions = {
                        // Edit + the management menu (block/disconnect/delete) act on a saved
                        // contact — meaningless for a not-yet-accepted incoming request, whose
                        // only actions are Accept/Reject in the profile card below (#921).
                        if (!uiState.isPendingIncoming) {
                            IconButton(onClick = { onAction(ContactDetailAction.EditClicked) }) {
                                Icon(
                                    Icons.Outlined.Edit,
                                    contentDescription = stringResource(MR.string.contactbook_detail_edit),
                                )
                            }
                            if (uiState.entry != null) {
                                ManagementMenu(uiState = uiState, onAction = onAction)
                            }
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val entry = uiState.entry
            when {
                entry == null && uiState.isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                entry == null -> {}

                // A pending incoming request has no connection-scoped data (contact fields,
                // groups-in-common, circles are empty; Activity needs a conversation and About
                // needs synced ext_data — none exist before connecting). Show a self-contained
                // public-profile card to inform Accept/Reject instead of the placeholder tabs
                // (#921). Once accepted, this same screen flips to the full detail below.
                uiState.isPendingIncoming -> PendingRequestProfile(
                    entry = entry,
                    assignableCircles = uiState.assignableCircles,
                    onAccept = { selectedCircleIds ->
                        onAction(ContactDetailAction.AcceptRequestClicked(selectedCircleIds))
                    },
                    onReject = { onAction(ContactDetailAction.RejectRequestClicked) },
                    actionInProgress = uiState.actionInProgress,
                    onAvatarClick = onAvatarClick,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                )

                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        DetailHeader(
                            uiState = uiState,
                            onAction = onAction,
                            onAvatarClick = onAvatarClick,
                            onConnect = onConnect,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                        )

                        if (contactDetailTabs.size > 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                            TabRow(selectedTabIndex = contactDetailTabs.indexOf(currentTab)) {
                                contactDetailTabs.forEach { tab ->
                                    Tab(
                                        selected = tab == currentTab,
                                        onClick = { onSelectTab(tab) },
                                        text = { Text(stringResource(tab.labelRes)) },
                                    )
                                }
                            }
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(tabScroll),
                        ) {
                            Spacer(
                                modifier = Modifier.height(
                                    if (contactDetailTabs.size > 1) 12.dp else 20.dp
                                )
                            )
                            when (currentTab) {
                                ContactDetailTab.DETAILS -> {
                                    uiState.introducedByName?.let { IntroducedBySection(it) }
                                    ContactFieldsSection(
                                        entry = entry,
                                        expanded = detailsExpanded,
                                        onToggleMore = onToggleDetails,
                                    )
                                    // Circles + groups-in-common only apply to Homebase identities.
                                    if (uiState.hasOdinId) {
                                        GroupsInCommonSection(
                                            groups = uiState.groupsInCommon,
                                            isConnected = uiState.isConnected,
                                            onOpenGroup = {
                                                onAction(ContactDetailAction.OpenGroup(it))
                                            },
                                        )
                                        CirclesSection(
                                            circles = uiState.circles,
                                            isConnected = uiState.isConnected,
                                            onCircleClicked = {
                                                onAction(ContactDetailAction.CircleClicked(it))
                                            },
                                        )
                                    }
                                }

                                ContactDetailTab.ABOUT -> {
                                    if (uiState.hasAboutContent) {
                                        // Bio, then social handles, then experience. All text here
                                        // is selectable/copyable (one selection scope for the whole
                                        // tab — it reads like a profile page).
                                        SelectionContainer {
                                            Column {
                                                BioSection(entry.shortBio)
                                                SocialSection(entry.socialHandles)
                                                ExperienceSection(
                                                    uiState.experience,
                                                    uiState.experienceImage,
                                                )
                                            }
                                        }
                                    } else {
                                        TabEmptyMessage(
                                            stringResource(MR.string.contactbook_detail_about_empty),
                                        )
                                    }
                                }

                                ContactDetailTab.ACTIVITY -> {
                                    if (uiState.hasActivityContent) {
                                        RecentMediaSection(
                                            overview = uiState.overview,
                                            onMediaClick = {
                                                onAction(ContactDetailAction.OpenMedia(it))
                                            },
                                            onSeeAll = {
                                                onAction(ContactDetailAction.SeeAllMediaClicked)
                                            },
                                        )
                                    } else {
                                        TabEmptyMessage(
                                            stringResource(MR.string.contactbook_detail_activity_empty),
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }

            ChatMediaFullScreenHost(
                item = uiState.fullScreenMedia,
                driveId = chatTargetDrive.alias,
                title = uiState.entry?.displayName.orEmpty(),
                snackbarHostState = snackbarHostState,
                onDismiss = { onAction(ContactDetailAction.CloseMedia) },
            )

            if (uiState.actionInProgress) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                        .pointerInput(Unit) {
                            // Swallow taps so the action can't be re-triggered while it runs.
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

/** Tabs on the contact detail screen; shown only when they have content. */
private enum class ContactDetailTab(val labelRes: StringResource) {
    DETAILS(MR.string.contactbook_detail_tab_details),
    ABOUT(MR.string.contactbook_detail_tab_about),
    ACTIVITY(MR.string.contactbook_detail_tab_activity),
}

// Always show all three; each renders a friendly empty state when it has nothing, so the
// contact's layout stays consistent.
private val contactDetailTabs = listOf(
    ContactDetailTab.DETAILS,
    ContactDetailTab.ACTIVITY,
    ContactDetailTab.ABOUT,
)

/**
 * Overflow (⋮) menu of management actions — sync, disconnect, block/unblock, delete — kept out of
 * the browsing flow. Destructive items are tinted error.
 */
@Composable
private fun ManagementMenu(
    uiState: ContactDetailUiState,
    onAction: (ContactDetailAction) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val error = MaterialTheme.colorScheme.error
    IconButton(onClick = { open = true }) {
        Icon(
            Icons.Default.MoreVert,
            contentDescription = stringResource(MR.string.contactbook_detail_manage),
        )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        if (uiState.hasOdinId) {
            DropdownMenuItem(
                text = { Text(stringResource(MR.string.contactbook_detail_sync)) },
                leadingIcon = { Icon(Icons.Outlined.Sync, contentDescription = null) },
                onClick = { open = false; onAction(ContactDetailAction.SyncClicked) },
            )
            if (uiState.isConnected) {
                DropdownMenuItem(
                    text = {
                        Text(stringResource(MR.string.contactbook_detail_disconnect), color = error)
                    },
                    leadingIcon = {
                        Icon(Icons.Outlined.PersonRemove, contentDescription = null, tint = error)
                    },
                    onClick = { open = false; onAction(ContactDetailAction.DisconnectClicked) },
                )
            }
            if (uiState.isBlocked) {
                DropdownMenuItem(
                    text = { Text(stringResource(MR.string.contactbook_detail_unblock)) },
                    leadingIcon = { Icon(Icons.Outlined.Block, contentDescription = null) },
                    onClick = { open = false; onAction(ContactDetailAction.UnblockClicked) },
                )
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(MR.string.contactbook_detail_block), color = error) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Block, contentDescription = null, tint = error)
                    },
                    onClick = { open = false; onAction(ContactDetailAction.BlockClicked) },
                )
            }
        }
        DropdownMenuItem(
            text = { Text(stringResource(MR.string.contactbook_detail_delete), color = error) },
            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = error) },
            onClick = { open = false; onAction(ContactDetailAction.DeleteClicked) },
        )
    }
}

@Composable
private fun DetailHeader(
    uiState: ContactDetailUiState,
    onAction: (ContactDetailAction) -> Unit,
    onAvatarClick: (SubSamplingImageSource) -> Unit,
    onConnect: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val entry = uiState.entry ?: return
    val status = uiState.connectionStatus
    val connected = status == ConnectionStatus.Connected
    val blocked = status == ConnectionStatus.Blocked
    val pending = status == ConnectionStatus.None
    // No incoming-request state here: a pending incoming request takes over the whole body with
    // [PendingRequestProfile] (which owns Accept/Reject plus the circle picker), so this header
    // only ever renders once that request is gone — accepted, rejected, or never there.
    val requestOutgoing = uiState.requestDirection == RequestDirection.OUTGOING
    // Has a Homebase identity but no active connection, pending request, or block.
    val canConnect = uiState.hasOdinId && !connected && !blocked && !pending &&
        uiState.requestDirection == null

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ContactBookAvatar(
            entry = entry,
            size = 88.dp,
            onClick = onAvatarClick,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
        )
        Spacer(modifier = Modifier.height(8.dp))
        // Name and Homebase ID are selectable so they can be copied (the ID especially).
        SelectionContainer {
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
        }
        entry.odinId?.let {
            SelectionContainer {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Free-text status/tagline the contact set, under the odinId.
        entry.status?.takeIf { it.isNotBlank() }?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }

        // Connection status line (only for Homebase contacts), with a small inline "sync profile"
        // affordance — identity contacts pull their details from the profile, so a quick re-sync
        // belongs right next to the status.
        if (uiState.hasOdinId) {
            val statusColor = when {
                connected -> MaterialTheme.colorScheme.primary
                blocked -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(
                        when {
                            connected -> MR.string.contactbook_connected
                            requestOutgoing -> MR.string.contactbook_detail_request_outgoing
                            pending -> MR.string.contactbook_detail_pending
                            blocked -> MR.string.contactbook_detail_blocked
                            else -> MR.string.contactbook_detail_not_connected
                        }
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                )
                IconButton(
                    onClick = { onAction(ContactDetailAction.SyncClicked) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Outlined.Sync,
                        contentDescription = stringResource(MR.string.contactbook_detail_sync),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // Emergency-contact indicator — visible whenever this contact is one of our emergency
        // contacts (independent of connection state).
        if (entry.iCanLocate) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.ContactEmergency,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(MR.string.contactbook_detail_emergency_badge),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // Freshness of the data we can see, from the last Sync-time temporal-access preflight.
            uiState.locateNewestDataAt?.let { newest ->
                val asOf = formatTimestamp(Instant.fromEpochMilliseconds(newest.milliseconds))
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(MR.string.contactbook_detail_location_data_as_of, asOf),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when {
            connected -> {
                Spacer(modifier = Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = { onAction(ContactDetailAction.MessageClicked) },
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Message, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(MR.string.contactbook_detail_message))
                }
            }
            canConnect -> {
                Spacer(modifier = Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = onConnect,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Outlined.PersonAddAlt1, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(MR.string.contactbook_detail_connect))
                }
            }
            requestOutgoing -> {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { onAction(ContactDetailAction.CancelRequestClicked) },
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(MR.string.contactbook_detail_cancel_request))
                }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    confirm: ContactDetailConfirm,
    isConnected: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (title, message, action) = when (confirm) {
        ContactDetailConfirm.BLOCK -> Triple(
            MR.string.contactbook_detail_block_title,
            MR.string.contactbook_detail_block_message,
            MR.string.contactbook_detail_block,
        )
        ContactDetailConfirm.DISCONNECT -> Triple(
            MR.string.contactbook_detail_disconnect_title,
            MR.string.contactbook_detail_disconnect_message,
            MR.string.contactbook_detail_disconnect,
        )
        ContactDetailConfirm.DELETE -> Triple(
            MR.string.contactbook_detail_delete_title,
            // Deleting a connected contact also tears down the connection — warn about that.
            if (isConnected) MR.string.contactbook_detail_delete_message_connected
            else MR.string.contactbook_detail_delete_message,
            MR.string.contactbook_detail_delete,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(action), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.string.cancel)) }
        },
    )
}
