package id.homebase.core.ui.screens.filesync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.common.OdinId
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.common.util.formatBytes
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.IdentityAvatar
import id.homebase.core.util.formatMediumDate
import id.homebase.core.widget.AdaptiveSheet
import id.homebase.core.widget.MinimalSearchTextField
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.filesync_access_edit
import id.homebase.resources.filesync_access_view
import id.homebase.resources.filesync_change_expiry
import id.homebase.resources.filesync_expires_in_days
import id.homebase.resources.filesync_expires_never
import id.homebase.resources.filesync_expires_on
import id.homebase.resources.filesync_expires_within_day
import id.homebase.resources.filesync_expiry_day
import id.homebase.resources.filesync_expiry_month
import id.homebase.resources.filesync_expiry_never
import id.homebase.resources.filesync_expiry_title
import id.homebase.resources.filesync_expiry_week
import id.homebase.resources.filesync_expiry_year
import id.homebase.resources.filesync_share_access_edit
import id.homebase.resources.filesync_share_access_view
import id.homebase.resources.filesync_share_no_people
import id.homebase.resources.filesync_share_person
import id.homebase.resources.filesync_share_search
import id.homebase.resources.filesync_share_send
import id.homebase.resources.filesync_share_with
import id.homebase.resources.filesync_shared_by_none
import id.homebase.resources.filesync_shared_files_failed
import id.homebase.resources.filesync_shared_with
import id.homebase.resources.filesync_shared_with_me
import id.homebase.resources.filesync_shared_with_none
import id.homebase.resources.filesync_sharing_body
import id.homebase.resources.filesync_sharing_title
import id.homebase.resources.filesync_sharing_turn_on
import id.homebase.resources.filesync_unshare
import id.homebase.resources.filesync_view_close
import id.homebase.resources.filesync_view_from_body
import id.homebase.resources.filesync_view_from_title
import id.homebase.resources.filesync_view_to_body
import id.homebase.resources.filesync_view_to_none
import id.homebase.resources.filesync_view_to_title
import id.homebase.resources.filesync_you_shared_with
import kotlin.time.Clock
import kotlin.time.Instant
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

private val FilesyncAccess.choice: StringResource
    get() = when (this) {
        FilesyncAccess.View -> MR.string.filesync_share_access_view
        FilesyncAccess.Edit -> MR.string.filesync_share_access_edit
    }

private val FilesyncAccess.label: StringResource
    get() = when (this) {
        FilesyncAccess.View -> MR.string.filesync_access_view
        FilesyncAccess.Edit -> MR.string.filesync_access_edit
    }

private val FilesyncExpiry.choice: StringResource
    get() = when (this) {
        FilesyncExpiry.Day -> MR.string.filesync_expiry_day
        FilesyncExpiry.Week -> MR.string.filesync_expiry_week
        FilesyncExpiry.Month -> MR.string.filesync_expiry_month
        FilesyncExpiry.Year -> MR.string.filesync_expiry_year
        FilesyncExpiry.Never -> MR.string.filesync_expiry_never
    }

@Composable
private fun expiryText(expiresAt: Long?): String {
    expiresAt ?: return stringResource(MR.string.filesync_expires_never)
    val days = ((expiresAt - Clock.System.now().toEpochMilliseconds()) / FilesyncExpiry.DAY_MS).toInt()
    return when {
        days < 1 -> stringResource(MR.string.filesync_expires_within_day)
        days <= 60 -> pluralStringResource(MR.plurals.filesync_expires_in_days, days, days)
        else -> stringResource(MR.string.filesync_expires_on, formatMediumDate(Instant.fromEpochMilliseconds(expiresAt)))
    }
}

/** Whom to share the files with, and how; first asks to turn sharing on when it's off. */
@Composable
internal fun FilesyncShareWithSheet(sharing: FilesyncSharingState, onAction: (FilesyncUiAction) -> Unit) {
    AdaptiveSheet(onDismiss = { onAction(FilesyncUiAction.DismissShareWith) }, maxWidth = 420.dp) {
        Column(modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (sharing.on) {
                PersonPicker(sharing, onAction)
            } else {
                TurnOnSharing(sharing.turningOn, onAction)
            }
        }
    }
}

@Composable
private fun TurnOnSharing(turningOn: Boolean, onAction: (FilesyncUiAction) -> Unit) {
    Text(stringResource(MR.string.filesync_sharing_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(MR.string.filesync_sharing_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { onAction(FilesyncUiAction.DismissShareWith) }) { Text(stringResource(MR.string.cancel)) }
        // Clickable again while waiting: back from the browser or the owner's page, it carries on.
        Button(onClick = { onAction(FilesyncUiAction.TurnOnSharing) }) {
            if (turningOn) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Text(stringResource(MR.string.filesync_sharing_turn_on))
        }
    }
}

@Composable
private fun PersonPicker(sharing: FilesyncSharingState, onAction: (FilesyncUiAction) -> Unit) {
    val connectionService: ConnectionService = koinInject()
    val connections by connectionService.connections.collectAsStateWithLifecycle()
    val search = rememberTextFieldState()
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var access by rememberSaveable { mutableStateOf(FilesyncAccess.View) }
    var expiry by rememberSaveable { mutableStateOf(FilesyncExpiry.Default) }
    val query = search.text.toString().trim()
    val people = remember(connections, query) {
        connections.map.values.filter { it.status == ConnectionStatus.Connected }.map { it.odinId }
            .filter { query.isEmpty() || it.domainName.contains(query, ignoreCase = true) }
            .sortedBy { it.domainName }
    }
    LaunchedEffect(sharing.offered) { if (access !in sharing.offered) sharing.offered.firstOrNull()?.let { access = it } }

    Text(stringResource(MR.string.filesync_share_with), style = MaterialTheme.typography.titleMedium)
    MinimalSearchTextField(textFieldState = search, placeHolderText = stringResource(MR.string.filesync_share_search), modifier = Modifier.fillMaxWidth())
    if (people.isEmpty()) {
        Text(stringResource(MR.string.filesync_share_no_people), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
        items(people, key = { it.domainName }) { person ->
            ListItem(
                headlineContent = { Text(person.domainName) },
                leadingContent = { IdentityAvatar(person, AvatarOptions(size = 36.dp)) },
                trailingContent = { RadioButton(selected = chosen == person.domainName, onClick = null) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.selectable(selected = chosen == person.domainName, role = Role.RadioButton) { chosen = person.domainName },
            )
        }
    }
    sharing.offered.forEach { option ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().selectable(selected = access == option, role = Role.RadioButton) { access = option },
        ) {
            RadioButton(selected = access == option, onClick = null)
            Text(stringResource(option.choice), modifier = Modifier.padding(start = 8.dp))
        }
    }
    Text(stringResource(MR.string.filesync_expiry_title), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilesyncExpiry.entries.forEach { option ->
            FilterChip(selected = expiry == option, onClick = { expiry = option }, label = { Text(stringResource(option.choice)) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { onAction(FilesyncUiAction.DismissShareWith) }) { Text(stringResource(MR.string.cancel)) }
        Button(
            enabled = chosen != null && access in sharing.offered && !sharing.sending,
            onClick = { chosen?.let { onAction(FilesyncUiAction.ShareWithPerson(OdinId(it), access, expiry)) } },
        ) {
            if (sharing.sending) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(stringResource(MR.string.filesync_share_send))
        }
    }
}

/** The info pane's "Shared with": the people the file is shared with, each with a way to stop. */
@Composable
internal fun FilesyncSharedWithSection(node: FilesyncNode, sharing: FilesyncSharingState, onAction: (FilesyncUiAction) -> Unit) {
    LaunchedEffect(node.nodeId) { onAction(FilesyncUiAction.ShowSharedWith(node.nodeId)) }
    val recipients = sharing.sharedWith?.takeIf { it.nodeId == node.nodeId }?.recipients
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(MR.string.filesync_shared_with), style = MaterialTheme.typography.titleSmall)
        when {
            !sharing.on -> Unit
            recipients == null -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
            recipients.isEmpty() -> Text(
                stringResource(MR.string.filesync_shared_with_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> recipients.forEach { recipient ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IdentityAvatar(recipient.odinId, AvatarOptions(size = 32.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(recipient.odinId.domainName, style = MaterialTheme.typography.bodyMedium)
                        val terms = listOf(stringResource(recipient.access.label), expiryText(recipient.expiresAt)).joinToString(" · ")
                        Text(
                            terms,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onAction(FilesyncUiAction.Unshare(node.nodeId, recipient.odinId)) }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(MR.string.filesync_unshare, recipient.odinId.domainName))
                    }
                }
            }
        }
        TextButton(onClick = { onAction(FilesyncUiAction.ShareWithConnection(listOf(node))) }) {
            Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(MR.string.filesync_share_person), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Below the root's own nodes: the people who share files with this identity, each opened like a folder. */
internal fun LazyListScope.sharerRows(sharers: List<OdinId>, onAction: (FilesyncUiAction) -> Unit) {
    if (sharers.isEmpty()) return
    item(key = "sharers") {
        Column {
            HorizontalDivider(modifier = Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp))
            Text(
                stringResource(MR.string.filesync_shared_with_me),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
            )
        }
    }
    items(sharers, key = { "sharer:${it.domainName}" }) { sharer ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable(role = Role.Button) { onAction(FilesyncUiAction.OpenSharer(sharer)) }
                .padding(horizontal = 16.dp),
        ) {
            IdentityAvatar(sharer, AvatarOptions(size = FilesyncTileSize))
            Text(sharer.domainName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

/** The wide sidebar's "Shared with": each person this identity shares files with; one shows those files. */
@Composable
internal fun ColumnScope.FilesyncRecipientsSidebar(sharing: FilesyncSharingState, onAction: (FilesyncUiAction) -> Unit) {
    if (sharing.recipients.isEmpty()) return
    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp))
    Text(
        text = stringResource(MR.string.filesync_you_shared_with),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
    )
    sharing.recipients.forEach { recipient ->
        NavigationDrawerItem(
            label = { Text(recipient.domainName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            icon = { IdentityAvatar(recipient, AvatarOptions(size = 24.dp)) },
            selected = sharing.recipient == recipient,
            onClick = { onAction(FilesyncUiAction.OpenRecipient(recipient)) },
            modifier = Modifier.height(40.dp),
        )
    }
}

/** The wide browser's centre while a person is picked in the sidebar: their files shared with this identity, or the reverse. */
@Composable
internal fun FilesyncShareView(sharing: FilesyncSharingState, transfer: FilesyncTransfer?, onAction: (FilesyncUiAction) -> Unit) {
    val person = sharing.sharer ?: sharing.recipient ?: return
    val incoming = sharing.sharer != null
    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(16.dp)) {
                IdentityAvatar(person, AvatarOptions(size = 40.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(if (incoming) MR.string.filesync_view_from_title else MR.string.filesync_view_to_title, person.domainName),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(if (incoming) MR.string.filesync_view_from_body else MR.string.filesync_view_to_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(onClick = { onAction(FilesyncUiAction.OpenSharer(null)) }) { Text(stringResource(MR.string.filesync_view_close)) }
            }
        }
        val failed = if (incoming) sharing.sharedFilesFailed else sharing.recipientFilesFailed
        val count = if (incoming) sharing.sharedFiles?.size else sharing.recipientFiles?.size
        when {
            failed -> Text(
                stringResource(MR.string.filesync_shared_files_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            count == null -> CircularProgressIndicator(modifier = Modifier.padding(horizontal = 24.dp).size(24.dp))
            count == 0 -> Text(
                stringResource(if (incoming) MR.string.filesync_shared_by_none else MR.string.filesync_view_to_none),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            incoming -> LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(sharing.sharedFiles.orEmpty(), key = { it.nodeId }) { node ->
                    FilesyncNodeRow(
                        node = node,
                        details = node.sharedDetails(),
                        trailing = {
                            SaveButton(
                                progress = transfer.progressOf(node.nodeId),
                                onSave = { onAction(FilesyncUiAction.SaveShared(node)) },
                                onCancel = { onAction(FilesyncUiAction.CancelTransfer) },
                            )
                        },
                    )
                }
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(sharing.recipientFiles.orEmpty(), key = { it.node.nodeId }) { share ->
                    FilesyncNodeRow(
                        node = share.node,
                        details = listOf(stringResource(share.access.label), expiryText(share.expiresAt), share.node.sharedDetails())
                            .filter { it.isNotEmpty() }.joinToString(" · "),
                        trailing = {
                            ExpiryMenu(onPick = { onAction(FilesyncUiAction.ChangeExpiry(share.node.nodeId, it)) })
                            IconButton(onClick = { onAction(FilesyncUiAction.StopSharing(share.node.nodeId)) }) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(MR.string.filesync_unshare, person.domainName))
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpiryMenu(onPick: (FilesyncExpiry) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.Schedule, contentDescription = stringResource(MR.string.filesync_change_expiry))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FilesyncExpiry.entries.forEach { option ->
                DropdownMenuItem(text = { Text(stringResource(option.choice)) }, onClick = {
                    open = false
                    onPick(option)
                })
            }
        }
    }
}

@Composable
private fun FilesyncNode.sharedDetails(): String =
    listOfNotNull(sizeBytes?.let { formatBytes(it) }, modifiedMs?.let { formatDayTime(it) }).joinToString(" · ")

/** What [FilesyncSharingState.sharer] shares with this identity, each file to save. */
@Composable
internal fun FilesyncSharedFilesSheet(sharing: FilesyncSharingState, transfer: FilesyncTransfer?, onAction: (FilesyncUiAction) -> Unit) {
    val sharer = sharing.sharer ?: return
    AdaptiveSheet(onDismiss = { onAction(FilesyncUiAction.OpenSharer(null)) }, maxWidth = 520.dp) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                IdentityAvatar(sharer, AvatarOptions(size = 36.dp))
                Text(sharer.domainName, style = MaterialTheme.typography.titleMedium)
            }
            val files = sharing.sharedFiles
            when {
                sharing.sharedFilesFailed -> Text(
                    stringResource(MR.string.filesync_shared_files_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                files == null -> CircularProgressIndicator(modifier = Modifier.padding(horizontal = 24.dp).size(24.dp))
                files.isEmpty() -> Text(
                    stringResource(MR.string.filesync_shared_by_none),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                else -> LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                    items(files, key = { it.nodeId }) { node ->
                        FilesyncNodeRow(
                            node = node,
                            details = node.sharedDetails(),
                            trailing = {
                                SaveButton(
                                    progress = transfer.progressOf(node.nodeId),
                                    onSave = { onAction(FilesyncUiAction.SaveShared(node)) },
                                    onCancel = { onAction(FilesyncUiAction.CancelTransfer) },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
