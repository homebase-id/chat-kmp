package id.homebase.chat.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.api.common.OdinId
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.IdentityAvatar
import id.homebase.core.widget.AdaptiveSheet
import id.homebase.core.widget.ButtonProgressContent
import id.homebase.core.widget.ContactName
import id.homebase.core.widget.MinimalSearchTextField
import id.homebase.resources.MR
import id.homebase.resources.audience_group_edit
import id.homebase.resources.audience_group_id
import id.homebase.resources.audience_group_members_body
import id.homebase.resources.audience_group_members_title
import id.homebase.resources.audience_group_saving
import id.homebase.resources.audience_group_waiting
import id.homebase.resources.audience_picker_search
import id.homebase.resources.cancel
import id.homebase.resources.close
import id.homebase.resources.save
import org.jetbrains.compose.resources.stringResource

/**
 * Who's in [group], and why it can't be picked when it can't. With [onSave], its members can be changed, starting there
 * when [startEditing]; [saving] shows the save running.
 */
@Composable
fun AudienceGroupSheet(
    group: AudienceGroup,
    onDismiss: () -> Unit,
    onSave: ((List<OdinId>) -> Unit)? = null,
    startEditing: Boolean = false,
    saving: Boolean = false,
) {
    var editing by rememberSaveable(group.id) { mutableStateOf(startEditing) }
    AdaptiveSheet(onDismiss = onDismiss, maxWidth = 420.dp) {
        Column(modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (editing && onSave != null) {
                EditMembers(group, saving, onCancel = { if (startEditing) onDismiss() else editing = false }, onSave = onSave)
            } else {
                Members(group, onDismiss, onEdit = onSave?.let { { editing = true } })
            }
        }
    }
}

@Composable
private fun Members(group: AudienceGroup, onDismiss: () -> Unit, onEdit: (() -> Unit)?) {
    Text(group.name, style = MaterialTheme.typography.titleMedium)
    group.unavailable?.let { reason ->
        Text(reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        Text(stringResource(MR.string.audience_group_id, group.id), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val waiting = stringResource(MR.string.audience_group_waiting)
    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
        items(group.members, key = { it.domainName }) { PersonRow(it) }
        items(group.pending, key = { "pending-${it.domainName}" }) { PersonRow(it, supporting = waiting) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onDismiss) { Text(stringResource(MR.string.close)) }
        if (onEdit != null) Button(onClick = onEdit) { Text(stringResource(MR.string.audience_group_edit)) }
    }
}

/** A person by name and avatar, [supporting] below (their Homebase ID unless given). */
@Composable
fun PersonRow(person: OdinId, supporting: String? = null, trailing: (@Composable () -> Unit)? = null) {
    ListItem(
        leadingContent = { IdentityAvatar(person, AvatarOptions(size = 36.dp)) },
        supportingContent = { Text(supporting ?: person.domainName) },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    ) { ContactName(person, knownName = null, style = MaterialTheme.typography.bodyLarge) }
}

/** Picks [group]'s members from the connections, the ones in it now first; at least one stays. */
@Composable
private fun EditMembers(group: AudienceGroup, saving: Boolean, onCancel: () -> Unit, onSave: (List<OdinId>) -> Unit) {
    val search = rememberTextFieldState()
    // The pending are in it too: unticking one takes them out before they join.
    val current = remember(group.members, group.pending) { (group.members + group.pending).mapTo(HashSet()) { it.domainName } }
    var chosen by rememberSaveable(group.id) { mutableStateOf(current.toList()) }
    val chosenSet = remember(chosen) { chosen.toSet() }
    val people = rememberPeople(search.text.toString().trim(), first = current, extra = group.members + group.pending)
    Text(stringResource(MR.string.audience_group_members_title, group.name), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(MR.string.audience_group_members_body, group.name), style = MaterialTheme.typography.bodyMedium)
    MinimalSearchTextField(textFieldState = search, placeHolderText = stringResource(MR.string.audience_picker_search), modifier = Modifier.fillMaxWidth())
    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
        items(people, key = { it.domainName }) { person ->
            val checked = person.domainName in chosenSet
            PersonCheckRow(person, checked, enabled = !saving) { chosen = if (checked) chosen - person.domainName else chosen + person.domainName }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onCancel, enabled = !saving) { Text(stringResource(MR.string.cancel)) }
        Button(
            enabled = !saving && chosen.isNotEmpty() && chosenSet != current,
            onClick = { onSave(chosen.map(::OdinId)) },
        ) { ButtonProgressContent(saving, stringResource(MR.string.audience_group_saving), stringResource(MR.string.save)) }
    }
}
