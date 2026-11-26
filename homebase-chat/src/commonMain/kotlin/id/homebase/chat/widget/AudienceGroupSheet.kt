package id.homebase.chat.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.api.common.OdinId
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.IdentityAvatar
import id.homebase.core.widget.AdaptiveSheet
import id.homebase.core.widget.ContactName
import id.homebase.core.widget.MinimalSearchTextField
import id.homebase.resources.MR
import id.homebase.resources.audience_group_edit
import id.homebase.resources.audience_group_id
import id.homebase.resources.audience_group_members_body
import id.homebase.resources.audience_group_members_title
import id.homebase.resources.audience_group_saving
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
    var editing by rememberSaveable(group.id) { mutableStateOf(startEditing && onSave != null) }
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
    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
        items(group.members, key = { it.domainName }) { PersonRow(it) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onDismiss) { Text(stringResource(MR.string.close)) }
        if (onEdit != null) Button(onClick = onEdit) { Text(stringResource(MR.string.audience_group_edit)) }
    }
}

/** A person by name and avatar, their Homebase ID below. */
@Composable
fun PersonRow(person: OdinId, supporting: String? = null) {
    ListItem(
        leadingContent = { IdentityAvatar(person, AvatarOptions(size = 36.dp)) },
        supportingContent = { Text(supporting ?: person.domainName) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    ) { ContactName(person, knownName = null, style = MaterialTheme.typography.bodyLarge) }
}

/** Picks [group]'s members from the connections, the ones in it now first; at least one stays. */
@Composable
private fun EditMembers(group: AudienceGroup, saving: Boolean, onCancel: () -> Unit, onSave: (List<OdinId>) -> Unit) {
    val search = rememberTextFieldState()
    var chosen by rememberSaveable(group.id) { mutableStateOf(group.members.map { it.domainName }) }
    val people = rememberPeople(search.text.toString().trim(), first = group.members.mapTo(HashSet()) { it.domainName }, extra = group.members)
    Text(stringResource(MR.string.audience_group_members_title, group.name), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(MR.string.audience_group_members_body, group.name), style = MaterialTheme.typography.bodyMedium)
    MinimalSearchTextField(textFieldState = search, placeHolderText = stringResource(MR.string.audience_picker_search), modifier = Modifier.fillMaxWidth())
    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
        items(people, key = { it.domainName }) { person ->
            val checked = person.domainName in chosen
            PersonCheckRow(person, checked, enabled = !saving) { chosen = if (checked) chosen - person.domainName else chosen + person.domainName }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onCancel, enabled = !saving) { Text(stringResource(MR.string.cancel)) }
        Button(
            enabled = !saving && chosen.isNotEmpty() && chosen.toSet() != group.members.mapTo(HashSet()) { it.domainName },
            onClick = { onSave(chosen.map(::OdinId)) },
        ) {
            if (saving) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(MR.string.audience_group_saving))
            } else {
                Text(stringResource(MR.string.save))
            }
        }
    }
}
