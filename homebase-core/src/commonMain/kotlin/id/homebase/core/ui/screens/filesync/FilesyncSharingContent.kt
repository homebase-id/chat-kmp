package id.homebase.core.ui.screens.filesync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.common.OdinId
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.common.util.formatBytes
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.IdentityAvatar
import id.homebase.core.widget.AdaptiveSheet
import id.homebase.core.widget.MinimalSearchTextField
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.filesync_access_edit
import id.homebase.resources.filesync_access_view
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
import org.jetbrains.compose.resources.StringResource
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { onAction(FilesyncUiAction.DismissShareWith) }) { Text(stringResource(MR.string.cancel)) }
        Button(
            enabled = chosen != null && access in sharing.offered && !sharing.sending,
            onClick = { chosen?.let { onAction(FilesyncUiAction.ShareWithPerson(OdinId(it), access)) } },
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
                        Text(stringResource(recipient.access.label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onAction(FilesyncUiAction.Unshare(node.nodeId, recipient.odinId)) }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(MR.string.filesync_unshare, recipient.odinId.domainName))
                    }
                }
            }
        }
        TextButton(onClick = { onAction(FilesyncUiAction.AskShare(listOf(node))) }) {
            Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(MR.string.filesync_share_person), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** The people who share files with this identity, at the top of the browser; one opens what they share. */
@Composable
internal fun FilesyncSharersStrip(sharers: List<OdinId>, onAction: (FilesyncUiAction) -> Unit) {
    if (sharers.isEmpty()) return
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(MR.string.filesync_shared_with_me), style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(sharers, key = { it.domainName }) { sharer ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable(role = Role.Button) { onAction(FilesyncUiAction.OpenSharer(sharer)) },
                ) {
                    IdentityAvatar(sharer, AvatarOptions(size = 48.dp))
                    Text(sharer.domainName.substringBefore('.'), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}

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
                            details = listOfNotNull(node.sizeBytes?.let { formatBytes(it) }, node.modifiedMs?.let { formatDayTime(it) }).joinToString(" · "),
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
