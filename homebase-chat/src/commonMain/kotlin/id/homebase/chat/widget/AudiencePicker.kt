package id.homebase.chat.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.common.OdinId
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.util.cleanDomain
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.AvatarStack
import id.homebase.core.avatars.IdentityAvatar
import id.homebase.core.widget.HomebaseIdField
import id.homebase.core.widget.MinimalSearchTextField
import id.homebase.resources.MR
import id.homebase.resources.add_contact_odinid_hint
import id.homebase.resources.audience_group_people
import id.homebase.resources.audience_group_see_members
import id.homebase.resources.audience_picker_add
import id.homebase.resources.audience_picker_group_name
import id.homebase.resources.audience_picker_groups
import id.homebase.resources.audience_picker_new_group_instead
import id.homebase.resources.audience_picker_no_people
import id.homebase.resources.audience_picker_people
import id.homebase.resources.audience_picker_same_people
import id.homebase.resources.audience_picker_search
import id.homebase.resources.audience_picker_typed
import id.homebase.resources.audience_picker_typed_invalid
import id.homebase.resources.audience_picker_typed_not_connected
import id.homebase.resources.audience_picker_with
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * A group an app has made of the people it shares with; [id] is its circle's. [pending] were added and join once their
 * identity next calls this one. [unavailable] says why it can't be picked.
 */
data class AudienceGroup(
    val id: String,
    val name: String,
    val members: List<OdinId>,
    val unavailable: String? = null,
    val pending: List<OdinId> = emptyList(),
)

sealed interface AudienceChoice {
    data class Group(val id: String) : AudienceChoice
    data class Person(val odinId: OdinId) : AudienceChoice
    data class NewGroup(val name: String, val members: List<OdinId>) : AudienceChoice
}

/** What the picker has picked; [choice] reads it against the groups shown. */
@Stable
class AudiencePickerState internal constructor(group: String?, people: List<String>, typed: List<String>, name: String, newGroupInstead: Boolean) {
    var chosenGroup by mutableStateOf(group)
        internal set
    var chosenPeople by mutableStateOf(people)
        internal set

    // Typed in rather than picked from the connections, so they stay listed.
    internal var typed by mutableStateOf(typed)
    internal var groupName by mutableStateOf(name)
    internal var newGroupInstead by mutableStateOf(newGroupInstead)

    // Picking the people of a group already made shares with it, unless a new one is asked for.
    internal fun sameGroup(groups: List<AudienceGroup>): AudienceGroup? =
        chosenPeople.toSet().takeIf { it.size > 1 }?.let { picked ->
            groups.firstOrNull { g -> g.unavailable == null && g.members.map { it.domainName }.toSet() == picked }
        }

    internal fun makesGroup(groups: List<AudienceGroup>): Boolean =
        chosenGroup == null && chosenPeople.size > 1 && (sameGroup(groups) == null || newGroupInstead)

    /** Null until enough is picked: a new group needs its name. */
    fun choice(groups: List<AudienceGroup>): AudienceChoice? {
        chosenGroup?.let { id -> return groups.firstOrNull { it.id == id && it.unavailable == null }?.let { AudienceChoice.Group(it.id) } }
        if (chosenPeople.size == 1) return AudienceChoice.Person(OdinId(chosenPeople.single()))
        if (!newGroupInstead) sameGroup(groups)?.let { return AudienceChoice.Group(it.id) }
        return groupName.trim().takeIf { chosenPeople.size > 1 && it.isNotEmpty() }?.let { AudienceChoice.NewGroup(it, chosenPeople.map(::OdinId)) }
    }

    fun clear() {
        chosenGroup = null
        chosenPeople = emptyList()
        typed = emptyList()
        groupName = ""
        newGroupInstead = false
    }

    internal fun pickGroup(id: String) {
        chosenGroup = id
        chosenPeople = emptyList()
    }

    internal fun togglePerson(domain: String) {
        chosenGroup = null
        newGroupInstead = false
        chosenPeople = if (domain in chosenPeople) chosenPeople - domain else chosenPeople + domain
    }

    internal fun addTyped(person: OdinId) {
        val domain = person.domainName
        if (domain !in typed) typed = typed + domain
        if (domain !in chosenPeople) togglePerson(domain)
    }

    internal companion object {
        val Saver = listSaver<AudiencePickerState, Any?>(
            save = { listOf(it.chosenGroup, it.chosenPeople, it.typed, it.groupName, it.newGroupInstead) },
            restore = {
                @Suppress("UNCHECKED_CAST")
                AudiencePickerState(it[0] as String?, it[1] as List<String>, it[2] as List<String>, it[3] as String, it[4] as Boolean)
            },
        )
    }
}

@Composable
fun rememberAudiencePickerState(): AudiencePickerState =
    rememberSaveable(saver = AudiencePickerState.Saver) { AudiencePickerState(null, emptyList(), emptyList(), "", false) }

/** A Homebase ID typed by hand, or null when it isn't one. */
fun typedIdentityOrNull(text: String): OdinId? =
    text.trim().lowercase().takeIf { it.contains('.') }?.let { runCatching { OdinId(it) }.getOrNull() }

/**
 * Whom to share with: one of [groups], or connected people, one or several (several make a group). Those in [first] are
 * listed first. With [typedFallback], a connection can also be added by typing their Homebase ID; one who isn't a
 * connection is said not to be, since a share reaches only connections. With
 * [onGroupMembers], each group has a button that shows who's in it, and a group that can't be picked opens it too.
 */
@Composable
fun AudiencePicker(
    state: AudiencePickerState,
    groups: List<AudienceGroup>,
    first: Set<String> = emptySet(),
    typedFallback: Boolean = false,
    enabled: Boolean = true,
    onGroupMembers: ((AudienceGroup) -> Unit)? = null,
) {
    val search = rememberTextFieldState()
    val query = search.text.toString().trim()
    val shownGroups = remember(groups, query) {
        groups.filter { group -> query.isEmpty() || group.name.contains(query, ignoreCase = true) || group.members.any { it.domainName.contains(query, ignoreCase = true) } }
    }
    val people = rememberPeople(query, first, state.typed.map(::OdinId))
    val sameGroup = remember(groups, state.chosenPeople) { state.sameGroup(groups) }

    Text(stringResource(MR.string.audience_picker_with), style = MaterialTheme.typography.titleMedium)
    MinimalSearchTextField(textFieldState = search, placeHolderText = stringResource(MR.string.audience_picker_search), modifier = Modifier.fillMaxWidth())
    if (shownGroups.isEmpty() && people.isEmpty()) {
        Text(stringResource(MR.string.audience_picker_no_people), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
        if (shownGroups.isNotEmpty()) {
            item(key = "groups") { PickerHeading(MR.string.audience_picker_groups) }
            items(shownGroups, key = { it.id }) { group ->
                GroupRow(group, selected = state.chosenGroup == group.id, enabled = enabled, onPick = { state.pickGroup(group.id) }, onMembers = onGroupMembers)
            }
        }
        if (people.isNotEmpty()) {
            item(key = "people") { PickerHeading(MR.string.audience_picker_people) }
            items(people, key = { it.domainName }) { person ->
                PersonCheckRow(person, checked = person.domainName in state.chosenPeople, enabled = enabled) { state.togglePerson(person.domainName) }
            }
        }
    }
    if (typedFallback) TypedPerson(enabled, notConnected = rememberNotConnected()) { state.addTyped(it) }
    if (sameGroup != null && !state.newGroupInstead) {
        Text(stringResource(MR.string.audience_picker_same_people, sameGroup.name), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { state.newGroupInstead = true }, enabled = enabled) { Text(stringResource(MR.string.audience_picker_new_group_instead)) }
    }
    if (state.makesGroup(groups)) {
        OutlinedTextField(
            value = state.groupName,
            onValueChange = { state.groupName = it },
            label = { Text(stringResource(MR.string.audience_picker_group_name)) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun GroupRow(group: AudienceGroup, selected: Boolean, enabled: Boolean, onPick: () -> Unit, onMembers: ((AudienceGroup) -> Unit)?) {
    val available = group.unavailable == null
    val pick = if (available) {
        Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onPick)
    } else {
        onMembers?.let { Modifier.clickable(enabled = enabled, role = Role.Button) { it(group) } } ?: Modifier
    }
    ListItem(
        supportingContent = {
            if (available) {
                Text(pluralStringResource(MR.plurals.audience_group_people, group.members.size, group.members.size))
            } else {
                Text(group.unavailable.orEmpty(), color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        leadingContent = { GroupFaces(group.members) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onMembers != null) {
                    IconButton(onClick = { onMembers(group) }, enabled = enabled) {
                        Icon(Icons.Outlined.Groups, contentDescription = stringResource(MR.string.audience_group_see_members, group.name))
                    }
                }
                RadioButton(selected = selected, onClick = null, enabled = enabled && available)
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = pick.alpha(if (available) 1f else 0.6f),
    ) { Text(group.name) }
}

/** A group's faces: its members' avatars overlapping, or the group tile when it has none. */
@Composable
fun GroupFaces(members: List<OdinId>, avatarSize: Dp = 24.dp) {
    if (members.isEmpty()) GroupTile(avatarSize + 12.dp) else AvatarStack(members, avatarSize = avatarSize)
}

// Whether someone isn't a connection; false until the connections have loaded, when the share's own check says it.
@Composable
private fun rememberNotConnected(): (OdinId) -> Boolean {
    val connectionService: ConnectionService = koinInject()
    val connections by connectionService.connections.collectAsStateWithLifecycle()
    return remember(connections) {
        { person -> connections.isLoaded && connections.map[person]?.status != ConnectionStatus.Connected }
    }
}

@Composable
private fun TypedPerson(enabled: Boolean, notConnected: (OdinId) -> Boolean, onAdd: (OdinId) -> Unit) {
    // The field holds the space-encoded text it shows as dots.
    var field by remember { mutableStateOf(TextFieldValue()) }
    var invalid by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf<OdinId?>(null) }
    val add = {
        val person = typedIdentityOrNull(field.text.cleanDomain(preserveTrailingDot = false, preserveTrailingDash = false))
        when {
            person == null -> invalid = true
            notConnected(person) -> refused = person
            else -> {
                onAdd(person)
                field = TextFieldValue()
            }
        }
    }
    HomebaseIdField(
        value = field,
        onValueChange = { incoming ->
            field = incoming.copy(text = incoming.text.cleanDomain().replace('.', ' '))
            invalid = false
            refused = null
        },
        label = { Text(stringResource(MR.string.audience_picker_typed)) },
        placeholder = { Text(stringResource(MR.string.add_contact_odinid_hint)) },
        supportingText = when {
            invalid -> {
                { Text(stringResource(MR.string.audience_picker_typed_invalid)) }
            }
            refused != null -> {
                { Text(stringResource(MR.string.audience_picker_typed_not_connected, refused?.domainName.orEmpty())) }
            }
            else -> null
        },
        trailingIcon = {
            IconButton(onClick = add, enabled = enabled && field.text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(MR.string.audience_picker_add))
            }
        },
        isError = invalid || refused != null,
        enabled = enabled,
        imeAction = ImeAction.Done,
        onImeAction = add,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Connected people whose name has [query], and [extra] who may not be connected; those in [first] first. */
@Composable
fun rememberPeople(query: String, first: Set<String>, extra: List<OdinId> = emptyList()): List<OdinId> {
    val connectionService: ConnectionService = koinInject()
    val connections by connectionService.connections.collectAsStateWithLifecycle()
    return remember(connections, query, first, extra) {
        (connections.map.values.filter { it.status == ConnectionStatus.Connected }.map { it.odinId } + extra).distinct()
            .filter { query.isEmpty() || it.domainName.contains(query, ignoreCase = true) }
            .sortedWith(compareBy<OdinId> { it.domainName !in first }.thenBy { it.domainName })
    }
}

@Composable
fun PersonCheckRow(person: OdinId, checked: Boolean, enabled: Boolean = true, onToggle: () -> Unit) {
    ListItem(
        leadingContent = { IdentityAvatar(person, AvatarOptions(size = 36.dp)) },
        trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Checkbox) { onToggle() },
    ) { Text(person.domainName) }
}

/** A group's round tile with the groups icon, where a person would have an avatar. */
@Composable
fun GroupTile(size: Dp) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(size * 0.6f))
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
