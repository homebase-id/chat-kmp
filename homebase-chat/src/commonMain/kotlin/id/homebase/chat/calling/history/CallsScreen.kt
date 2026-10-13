@file:OptIn(ExperimentalMaterial3Api::class)

package id.homebase.chat.calling.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddIcCall
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.common.OdinId
import id.homebase.chat.calling.CallEventDescriptor
import id.homebase.chat.calling.CallLogDirection
import id.homebase.chat.calling.CallOutcome
import id.homebase.chat.calling.callEventVisual
import id.homebase.chat.calling.imageVector
import id.homebase.chat.calling.spokenCallDuration
import id.homebase.chat.calling.stringRes
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ContactAvatar
import id.homebase.core.avatars.ConversationAvatar
import id.homebase.core.util.formatMediumDate
import id.homebase.core.util.formatTime
import id.homebase.resources.MR
import id.homebase.resources.call_button_video
import id.homebase.resources.call_button_voice
import id.homebase.resources.call_event_duration
import id.homebase.resources.calls_delete_confirm_body
import id.homebase.resources.calls_delete_confirm_title
import id.homebase.resources.calls_menu_delete
import id.homebase.resources.calls_menu_open_chat
import id.homebase.resources.chat_error_delete_message_me
import id.homebase.resources.calls_empty_body
import id.homebase.resources.calls_empty_title
import id.homebase.resources.calls_more_options
import id.homebase.resources.calls_new_call
import id.homebase.resources.calls_show_in_nav
import id.homebase.resources.calls_title
import id.homebase.resources.calls_today
import id.homebase.resources.calls_yesterday
import id.homebase.resources.cancel
import id.homebase.resources.delete
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

private const val LOAD_MORE_THRESHOLD = 5

@Composable
fun CallsScreen(
    viewModel: CallsViewModel,
    showInNav: Boolean,
    onShowInNavChange: (Boolean) -> Unit,
    onStartCall: (peer: OdinId, video: Boolean) -> Unit,
    onOpenConversation: (conversationId: Uuid, messageId: Uuid?) -> Unit,
    onNewCall: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val missedCount by viewModel.missedCount.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel, missedCount) {
        viewModel.onAction(CallsAction.TabOpened)
        onPauseOrDispose {}
    }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is CallsEvent.DeleteFailed ->
                    snackbarHostState.showSnackbar(getString(MR.string.chat_error_delete_message_me, event.reason.orEmpty()))
                is CallsEvent.StartCall -> onStartCall(event.peer, event.video)
                is CallsEvent.OpenConversation -> onOpenConversation(event.conversationId, event.messageId)
            }
        }
    }
    CallsContent(
        state = state,
        onAction = viewModel::onAction,
        showInNav = showInNav,
        onShowInNavChange = onShowInNavChange,
        onNewCall = onNewCall,
        modifier = modifier,
        listState = listState,
        snackbarHostState = snackbarHostState,
    )
}

@Composable
fun CallsContent(
    state: CallsUiState,
    onAction: (CallsAction) -> Unit,
    showInNav: Boolean,
    onShowInNavChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onNewCall: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    avatar: @Composable (CallRowUi) -> Unit = { row -> CallRowAvatar(row) },
) {
    var pendingDelete by remember { mutableStateOf<CallRowUi?>(null) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.calls_title)) },
                actions = { NavVisibilityMenu(showInNav, onShowInNavChange) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewCall) {
                Icon(Icons.Default.AddIcCall, contentDescription = stringResource(MR.string.calls_new_call))
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.isEmpty) {
                EmptyCalls(onNewCall)
            } else {
                CallList(state, onAction, listState, avatar, onDelete = { pendingDelete = it })
            }
        }
    }
    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(MR.string.calls_delete_confirm_title)) },
            text = { Text(stringResource(MR.string.calls_delete_confirm_body, row.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onAction(CallsAction.Delete(row.entry.messageId))
                }) { Text(stringResource(MR.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(MR.string.cancel)) } },
        )
    }
}

@Composable
private fun NavVisibilityMenu(showInNav: Boolean, onChange: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(MR.string.calls_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(MR.string.calls_show_in_nav)) },
                trailingIcon = { Checkbox(checked = showInNav, onCheckedChange = null) },
                onClick = {
                    expanded = false
                    onChange(!showInNav)
                },
            )
        }
    }
}

@Composable
private fun CallList(
    state: CallsUiState,
    onAction: (CallsAction) -> Unit,
    listState: LazyListState,
    avatar: @Composable (CallRowUi) -> Unit,
    onDelete: (CallRowUi) -> Unit,
) {
    val rowCount = state.sections.sumOf { it.rows.size }
    LaunchedEffect(listState, state.hasMore, rowCount) {
        if (!state.hasMore) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }.collect { nearEnd -> if (nearEnd) onAction(CallsAction.LoadMore) }
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        state.sections.forEach { section ->
            stickyHeader(key = "day_${section.day}") { DayHeader(section.day) }
            items(section.rows, key = { it.entry.messageId }) { row ->
                CallRow(row, onAction, onDelete, avatar)
            }
        }
    }
}

@Composable
private fun DayHeader(day: CallDay) {
    val label = when (day) {
        CallDay.Today -> stringResource(MR.string.calls_today)
        CallDay.Yesterday -> stringResource(MR.string.calls_yesterday)
        is CallDay.On -> formatMediumDate(day.date.atStartOfDayIn(TimeZone.currentSystemDefault()))
    }
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallRow(
    row: CallRowUi,
    onAction: (CallsAction) -> Unit,
    onDelete: (CallRowUi) -> Unit,
    avatar: @Composable (CallRowUi) -> Unit,
) {
    val entry = row.entry
    val colors = MaterialTheme.colorScheme
    val visual = callEventVisual(CallEventDescriptor(entry.outcome, entry.video, entry.durationSeconds), entry.direction == CallLogDirection.Outgoing)
    var menuOpen by remember { mutableStateOf(false) }
    val subdued = if (visual.missed) colors.error else colors.onSurfaceVariant
    val kind = stringResource(if (entry.video) MR.string.call_button_video else MR.string.call_button_voice)
    val title = stringResource(visual.title.stringRes())
    val withKind = stringResource(MR.string.call_event_duration, title, kind)
    val subtitle = if (entry.outcome == CallOutcome.Completed && entry.durationSeconds > 0) {
        stringResource(MR.string.call_event_duration, withKind, spokenCallDuration(entry.durationSeconds))
    } else withKind
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) menuOpen = true
                        }
                    }
                }
                .combinedClickable(
                    onClick = { onAction(CallsAction.OpenChat(entry.conversationId, entry.messageId)) },
                    onLongClick = { menuOpen = true },
                )
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            avatar(row)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (visual.missed) colors.error else colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        formatTime(entry.at),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(visual.icon.imageVector(), contentDescription = null, tint = subdued, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = subdued, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(enabled = row.canCall, onClick = { onAction(CallsAction.CallBack(entry, entry.video)) }) {
                Icon(
                    if (entry.video) Icons.Default.Videocam else Icons.Default.Call,
                    contentDescription = stringResource(if (entry.video) MR.string.call_button_video else MR.string.call_button_voice),
                )
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(MR.string.calls_more_options))
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(MR.string.calls_menu_open_chat)) },
                onClick = {
                    menuOpen = false
                    onAction(CallsAction.OpenChat(entry.conversationId))
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(MR.string.calls_menu_delete)) },
                onClick = {
                    menuOpen = false
                    onDelete(row)
                },
            )
        }
    }
}

@Composable
private fun CallRowAvatar(row: CallRowUi) {
    val model = row.avatarModel
    if (model != null) {
        ConversationAvatar(model, options = AvatarOptions())
    } else {
        ContactAvatar(odinId = row.entry.peer, profileImageData = null, initials = row.avatarInitials, options = AvatarOptions())
    }
}

@Composable
private fun EmptyCalls(onNewCall: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Call,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        Text(stringResource(MR.string.calls_empty_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(4.dp))
        Text(
            stringResource(MR.string.calls_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        FilledTonalButton(onClick = onNewCall) { Text(stringResource(MR.string.calls_new_call)) }
    }
}
