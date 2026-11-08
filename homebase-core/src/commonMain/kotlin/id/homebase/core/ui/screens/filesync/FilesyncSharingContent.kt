package id.homebase.core.ui.screens.filesync

import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.fileshare.FileShareDescriptor
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.UploadFile
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import id.homebase.resources.filesync_replace
import id.homebase.resources.filesync_upload
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.Dp
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
import id.homebase.resources.filesync_group_edit_members
import id.homebase.resources.filesync_group_members_body
import id.homebase.resources.filesync_group_members_title
import id.homebase.resources.filesync_group_name
import id.homebase.resources.filesync_group_new_instead
import id.homebase.resources.filesync_group_same_people
import id.homebase.resources.filesync_share_access_edit
import id.homebase.resources.filesync_share_access_view
import id.homebase.resources.filesync_share_groups
import id.homebase.resources.filesync_share_no_people
import id.homebase.resources.filesync_share_note
import id.homebase.resources.filesync_share_people
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
import id.homebase.resources.menu_back
import id.homebase.resources.save
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
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
    val days = ((expiresAt - Clock.System.now().toEpochMilliseconds()) / 1.days.inWholeMilliseconds).toInt()
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
    val search = rememberTextFieldState()
    var chosenGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var chosenPeople by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var groupName by rememberSaveable { mutableStateOf("") }
    var newGroupInstead by rememberSaveable { mutableStateOf(false) }
    var access by rememberSaveable { mutableStateOf(FilesyncAccess.View) }
    var expiry by rememberSaveable { mutableStateOf(FilesyncExpiry.Default) }
    var note by rememberSaveable { mutableStateOf("") }
    val query = search.text.toString().trim()
    val groups = remember(sharing.recipients, query) {
        sharing.recipients.filterIsInstance<FilesyncAudience.Group>().filter { group ->
            query.isEmpty() || group.name.contains(query, ignoreCase = true) || group.members.any { it.domainName.contains(query, ignoreCase = true) }
        }
    }
    val people = rememberPeople(query, first = sharing.recipients.filterIsInstance<FilesyncAudience.Person>().mapTo(HashSet()) { it.odinId.domainName })
    // Picking the people of a group already made shares with it, unless a new one is asked for.
    val sameGroup = remember(sharing.recipients, chosenPeople) {
        chosenPeople.toSet().takeIf { it.size > 1 }?.let { picked ->
            sharing.recipients.filterIsInstance<FilesyncAudience.Group>().firstOrNull { g -> g.members.map { it.domainName }.toSet() == picked }
        }
    }
    val audience: FilesyncAudience? = when {
        chosenGroup != null -> sharing.recipients.filterIsInstance<FilesyncAudience.Group>().firstOrNull { it.circleId.toString() == chosenGroup }
        chosenPeople.size == 1 -> FilesyncAudience.Person(OdinId(chosenPeople.single()))
        sameGroup != null && !newGroupInstead -> sameGroup
        else -> null
    }
    val makesGroup = audience == null && chosenPeople.size > 1
    LaunchedEffect(sharing.offered) { if (access !in sharing.offered) sharing.offered.firstOrNull()?.let { access = it } }

    Text(stringResource(MR.string.filesync_share_with), style = MaterialTheme.typography.titleMedium)
    MinimalSearchTextField(textFieldState = search, placeHolderText = stringResource(MR.string.filesync_share_search), modifier = Modifier.fillMaxWidth())
    if (groups.isEmpty() && people.isEmpty()) {
        Text(stringResource(MR.string.filesync_share_no_people), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
        if (groups.isNotEmpty()) {
            item(key = "groups") { PickerHeading(MR.string.filesync_share_groups) }
            items(groups, key = { it.circleId.toString() }) { group ->
                val selected = chosenGroup == group.circleId.toString()
                val members = group.members.joinToString { it.domainName }
                ListItem(
                    headlineContent = { Text(group.name) },
                    supportingContent = { Text(members, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { AudienceAvatar(group, 36.dp) },
                    trailingContent = { RadioButton(selected = selected, onClick = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.selectable(selected = selected, role = Role.RadioButton) {
                        chosenGroup = group.circleId.toString()
                        chosenPeople = emptyList()
                    },
                )
            }
        }
        if (people.isNotEmpty()) {
            item(key = "people") { PickerHeading(MR.string.filesync_share_people) }
            items(people, key = { it.domainName }) { person ->
                val checked = person.domainName in chosenPeople
                PersonCheckRow(person, checked) {
                    chosenGroup = null
                    newGroupInstead = false
                    chosenPeople = if (checked) chosenPeople - person.domainName else chosenPeople + person.domainName
                }
            }
        }
    }
    if (sameGroup != null && !newGroupInstead) {
        Text(stringResource(MR.string.filesync_group_same_people, sameGroup.name), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { newGroupInstead = true }) { Text(stringResource(MR.string.filesync_group_new_instead)) }
    }
    if (makesGroup) {
        OutlinedTextField(
            value = groupName,
            onValueChange = { groupName = it },
            label = { Text(stringResource(MR.string.filesync_group_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
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
    OutlinedTextField(
        value = note,
        // The chat message carries no more.
        onValueChange = { note = it.truncateToCodePoints(FileShareDescriptor.MAX_NOTE_CODEPOINTS) },
        label = { Text(stringResource(MR.string.filesync_share_note)) },
        maxLines = 4,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { onAction(FilesyncUiAction.DismissShareWith) }) { Text(stringResource(MR.string.cancel)) }
        Button(
            enabled = (audience != null || (makesGroup && groupName.isNotBlank())) && access in sharing.offered && !sharing.sending,
            onClick = {
                when {
                    audience != null -> onAction(FilesyncUiAction.ShareWith(audience, access, expiry, note))
                    makesGroup -> onAction(FilesyncUiAction.ShareWithNewGroup(groupName.trim(), chosenPeople.map(::OdinId), access, expiry, note))
                }
            },
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
                    AudienceAvatar(recipient.audience, 32.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(recipient.audience.label, style = MaterialTheme.typography.bodyMedium)
                        val terms = listOf(stringResource(recipient.access.label), expiryText(recipient.expiresAt)).joinToString(" · ")
                        Text(
                            terms,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onAction(FilesyncUiAction.Unshare(node, recipient.audience)) }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(MR.string.filesync_unshare, recipient.audience.label))
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

@Composable
private fun PickerHeading(text: StringResource) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/** A person's avatar, or for a group a round tile with the groups icon. */
@Composable
internal fun AudienceAvatar(audience: FilesyncAudience, size: Dp) {
    when (audience) {
        is FilesyncAudience.Person -> IdentityAvatar(audience.odinId, AvatarOptions(size = size))
        is FilesyncAudience.Group -> Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.size(size)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(size * 0.6f))
            }
        }
    }
}

/** The wide sidebar's "Shared with": each group and person this identity shares files with; one shows those files. */
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
            label = { Text(recipient.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            icon = { AudienceAvatar(recipient, 24.dp) },
            selected = sharing.recipient == recipient,
            onClick = { onAction(FilesyncUiAction.OpenRecipient(recipient)) },
            modifier = Modifier.height(40.dp),
        )
    }
}

/** The wide browser's centre while a person is picked in the sidebar: their files shared with this identity, or the reverse. */
@Composable
internal fun FilesyncShareView(
    sharing: FilesyncSharingState,
    transfer: FilesyncTransfer?,
    uploads: List<FilesyncUpload>,
    onAction: (FilesyncUiAction) -> Unit,
) {
    val shown = sharing.sharer?.let { FilesyncAudience.Person(it) } ?: sharing.recipient ?: return
    val incoming = sharing.sharer != null
    val writes = rememberSharedWrites(onAction)
    var editingMembers by rememberSaveable { mutableStateOf(false) }
    if (editingMembers && shown is FilesyncAudience.Group) {
        GroupMembersSheet(shown, onDismiss = { editingMembers = false }) { members ->
            editingMembers = false
            onAction(FilesyncUiAction.SetGroupMembers(shown, members))
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(16.dp)) {
                AudienceAvatar(shown, 40.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(if (incoming) MR.string.filesync_view_from_title else MR.string.filesync_view_to_title, shown.label),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(if (incoming) MR.string.filesync_view_from_body else MR.string.filesync_view_to_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!incoming && shown is FilesyncAudience.Group) {
                        val members = shown.members.joinToString { it.domainName }
                        Text(members, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { editingMembers = true }) { Text(stringResource(MR.string.filesync_group_edit_members)) }
                    }
                }
                if (incoming && sharing.canAddHere) FilesyncUploadButton(writes.pickToAdd)
                TextButton(onClick = { onAction(FilesyncUiAction.CloseShareView) }) { Text(stringResource(MR.string.filesync_view_close)) }
            }
        }
        if (incoming) {
            IncomingFiles(sharing, transfer, uploads, writes, onAction, gutter = 16.dp, listModifier = Modifier.fillMaxSize().padding(horizontal = 8.dp))
        } else SharedFilesBody(sharing.recipientFilesFailed, sharing.recipientFiles?.size, MR.string.filesync_view_to_none) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(sharing.recipientFiles.orEmpty(), key = { it.node.nodeId }) { share ->
                    FilesyncNodeRow(
                        node = share.node,
                        details = listOf(stringResource(share.access.label), expiryText(share.expiresAt), share.node.sharedDetails())
                            .filter { it.isNotEmpty() }.joinToString(" · "),
                        trailing = {
                            ExpiryMenu(onPick = { onAction(FilesyncUiAction.ChangeExpiry(share.node.nodeId, it)) })
                            IconButton(onClick = { onAction(FilesyncUiAction.StopSharing(share.node.nodeId)) }) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(MR.string.filesync_unshare, shown.label))
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Connected people whose name has [query], and [extra] who may no longer be connected; those in [first] first. */
@Composable
private fun rememberPeople(query: String, first: Set<String>, extra: List<OdinId> = emptyList()): List<OdinId> {
    val connectionService: ConnectionService = koinInject()
    val connections by connectionService.connections.collectAsStateWithLifecycle()
    return remember(connections, query, first, extra) {
        (connections.map.values.filter { it.status == ConnectionStatus.Connected }.map { it.odinId } + extra).distinct()
            .filter { query.isEmpty() || it.domainName.contains(query, ignoreCase = true) }
            .sortedWith(compareBy<OdinId> { it.domainName !in first }.thenBy { it.domainName })
    }
}

@Composable
private fun PersonCheckRow(person: OdinId, checked: Boolean, onToggle: () -> Unit) {
    ListItem(
        headlineContent = { Text(person.domainName) },
        leadingContent = { IdentityAvatar(person, AvatarOptions(size = 36.dp)) },
        trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.toggleable(value = checked, role = Role.Checkbox) { onToggle() },
    )
}

// A shared list's failure, loading and empty states; [list] once there is something to show.
@Composable
private fun SharedFilesBody(failed: Boolean, count: Int?, empty: StringResource, list: @Composable () -> Unit) {
    when {
        failed -> Text(
            stringResource(MR.string.filesync_shared_files_failed),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        count == null -> CircularProgressIndicator(modifier = Modifier.padding(horizontal = 24.dp).size(24.dp))
        count == 0 -> Text(stringResource(empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
        else -> list()
    }
}

/** Picks [group]'s members from the connections, the ones in it now first; at least one stays. */
@Composable
private fun GroupMembersSheet(group: FilesyncAudience.Group, onDismiss: () -> Unit, onSave: (List<OdinId>) -> Unit) {
    val search = rememberTextFieldState()
    var chosen by rememberSaveable { mutableStateOf(group.members.map { it.domainName }) }
    val people = rememberPeople(search.text.toString().trim(), first = group.members.mapTo(HashSet()) { it.domainName }, extra = group.members)
    AdaptiveSheet(onDismiss = onDismiss, maxWidth = 420.dp) {
        Column(modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(MR.string.filesync_group_members_title, group.name), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(MR.string.filesync_group_members_body, group.name), style = MaterialTheme.typography.bodyMedium)
            MinimalSearchTextField(textFieldState = search, placeHolderText = stringResource(MR.string.filesync_share_search), modifier = Modifier.fillMaxWidth())
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(people, key = { it.domainName }) { person ->
                    val checked = person.domainName in chosen
                    PersonCheckRow(person, checked) { chosen = if (checked) chosen - person.domainName else chosen + person.domainName }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text(stringResource(MR.string.cancel)) }
                Button(
                    enabled = chosen.isNotEmpty() && chosen.toSet() != group.members.mapTo(HashSet()) { it.domainName },
                    onClick = { onSave(chosen.map(::OdinId)) },
                ) { Text(stringResource(MR.string.save)) }
            }
        }
    }
}

// A shared folder opens in place; a shared file saves.
@Composable
private fun IncomingRow(
    node: FilesyncNode,
    path: List<FilesyncCrumb>,
    transfer: FilesyncTransfer?,
    onAction: (FilesyncUiAction) -> Unit,
    onReplace: ((FilesyncNode) -> Unit)? = null,
) {
    if (node.isFolder) {
        FilesyncNodeRow(
            node = node,
            details = "",
            modifier = Modifier.clickable(role = Role.Button) { onAction(FilesyncUiAction.OpenSharedFolder(path + FilesyncCrumb(node.nodeId, node.name))) },
        )
        return
    }
    FilesyncNodeRow(
        node = node,
        details = node.sharedDetails(),
        trailing = {
            if (onReplace != null) {
                IconButton(onClick = { onReplace(node) }) {
                    Icon(Icons.Outlined.UploadFile, contentDescription = stringResource(MR.string.filesync_replace, node.name))
                }
            }
            SaveButton(
                progress = transfer.progressOf(node.nodeId),
                onSave = { onAction(FilesyncUiAction.SaveShared(node)) },
                onCancel = { onAction(FilesyncUiAction.CancelTransfer) },
            )
        },
    )
}

/** What the sharer shows, below any of this identity's files on their way there. */
@Composable
private fun IncomingFiles(
    sharing: FilesyncSharingState,
    transfer: FilesyncTransfer?,
    uploads: List<FilesyncUpload>,
    writes: SharedWrites,
    onAction: (FilesyncUiAction) -> Unit,
    gutter: Dp,
    listModifier: Modifier,
) {
    if (uploads.isNotEmpty()) UploadStrip(uploads, onAction, Modifier.padding(horizontal = gutter).padding(bottom = 8.dp))
    SharedFilesBody(sharing.sharedFilesFailed, sharing.sharedFiles?.size, MR.string.filesync_shared_by_none) {
        LazyColumn(modifier = listModifier) {
            items(sharing.sharedFiles.orEmpty(), key = { it.nodeId }) { node ->
                IncomingRow(node, sharing.sharerPath, transfer, onAction, onReplace = writes.pickToReplace.takeIf { node.nodeId in sharing.editableIds })
            }
        }
    }
}

/** The pickers for adding files to the sharer's folder shown and replacing one of theirs. */
private class SharedWrites(val pickToAdd: () -> Unit, val pickToReplace: (FilesyncNode) -> Unit)

@Composable
private fun rememberSharedWrites(onAction: (FilesyncUiAction) -> Unit): SharedWrites {
    val add = rememberFilePickerLauncher(type = FileKitType.File(), mode = FileKitMode.Multiple()) { files ->
        if (!files.isNullOrEmpty()) onAction(FilesyncUiAction.AddToShared(files))
    }
    var replacing by remember { mutableStateOf<FilesyncNode?>(null) }
    val replace = rememberFilePickerLauncher(type = FileKitType.File(), mode = FileKitMode.Single) { file ->
        val node = replacing
        replacing = null
        if (file != null && node != null) onAction(FilesyncUiAction.ReplaceShared(node, file))
    }
    return remember(add, replace) {
        SharedWrites(pickToAdd = { add.launch() }, pickToReplace = { node ->
            replacing = node
            replace.launch()
        })
    }
}

@Composable
internal fun FilesyncUploadButton(onClick: () -> Unit) {
    Button(onClick = onClick) {
        Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(MR.string.filesync_upload))
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
internal fun FilesyncSharedFilesSheet(
    sharing: FilesyncSharingState,
    transfer: FilesyncTransfer?,
    uploads: List<FilesyncUpload>,
    onAction: (FilesyncUiAction) -> Unit,
) {
    val sharer = sharing.sharer ?: return
    val writes = rememberSharedWrites(onAction)
    AdaptiveSheet(onDismiss = { onAction(FilesyncUiAction.CloseShareView) }, maxWidth = 520.dp) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                val path = sharing.sharerPath
                if (path.isNotEmpty()) {
                    IconButton(onClick = { onAction(FilesyncUiAction.OpenSharedFolder(path.dropLast(1))) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(MR.string.menu_back))
                    }
                }
                IdentityAvatar(sharer, AvatarOptions(size = 36.dp))
                Text(path.lastOrNull()?.name ?: sharer.domainName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (sharing.canAddHere) FilesyncUploadButton(writes.pickToAdd)
            }
            IncomingFiles(sharing, transfer, uploads, writes, onAction, gutter = 24.dp, listModifier = Modifier.heightIn(max = 480.dp))
        }
    }
}
