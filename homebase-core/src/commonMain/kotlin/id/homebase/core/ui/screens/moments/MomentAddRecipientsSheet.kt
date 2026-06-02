package id.homebase.core.ui.screens.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import id.homebase.api.common.OdinId
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.FallbackAvatar
import id.homebase.core.avatars.PublicAvatar
import id.homebase.core.moments.services.MomentsRecipient
import id.homebase.core.moments.services.MomentsRecipientId
import id.homebase.core.moments.services.MomentsRecipientsSnapshot
import id.homebase.resources.MR
import id.homebase.resources.moments_add_people_already_shared
import id.homebase.resources.moments_add_people_confirm
import id.homebase.resources.moments_add_people_title
import id.homebase.resources.moments_audience_section_contacts
import id.homebase.resources.moments_audience_section_groups
import id.homebase.resources.moments_audience_section_recent
import id.homebase.resources.moments_create_search_hint
import id.homebase.resources.moments_create_selected_count
import id.homebase.resources.number_of_members
import org.jetbrains.compose.resources.stringResource

/**
 * "Add people" sheet for widening an already-posted moment's audience. Reuses
 * the compose-time recipient candidates ([MomentsRecipientsSnapshot]) but is
 * add-only: recipients already on the moment render locked ("Already shared")
 * and aren't selectable, so the author can grow the audience but never remove
 * someone who already received it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentAddRecipientsSheet(
    snapshot: MomentsRecipientsSnapshot,
    query: String,
    selected: Set<MomentsRecipientId>,
    existingRecipients: Set<OdinId>,
    isAdding: Boolean,
    onQueryChange: (String) -> Unit,
    onToggle: (MomentsRecipientId) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // A candidate is "already shared" when every OdinId it would send to is
    // already on the moment. Individuals: their single id; groups: all members.
    fun MomentsRecipient.alreadyShared(): Boolean =
        odinIds.isNotEmpty() && existingRecipients.containsAll(odinIds)

    fun List<MomentsRecipient>.matching(): List<MomentsRecipient> =
        if (query.isBlank()) this
        else filter { it.displayName.contains(query, ignoreCase = true) }

    val recent = snapshot.recent.matching()
    val groups = snapshot.groups.matching()
    val contacts = snapshot.contacts.matching()

    val recentLabel = stringResource(MR.string.moments_audience_section_recent)
    val groupsLabel = stringResource(MR.string.moments_audience_section_groups)
    val contactsLabel = stringResource(MR.string.moments_audience_section_contacts)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
        ) {
            Text(
                text = stringResource(MR.string.moments_add_people_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(MR.string.moments_create_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                if (recent.isNotEmpty()) {
                    section(recentLabel)
                    items(recent, key = { "r-${it.id.raw}" }) { recipient ->
                        AddRecipientRow(
                            recipient = recipient,
                            locked = recipient.alreadyShared(),
                            selected = recipient.id in selected,
                            onClick = { onToggle(recipient.id) },
                        )
                    }
                }
                if (groups.isNotEmpty()) {
                    section(groupsLabel)
                    items(groups, key = { "g-${it.id.raw}" }) { recipient ->
                        AddRecipientRow(
                            recipient = recipient,
                            locked = recipient.alreadyShared(),
                            selected = recipient.id in selected,
                            onClick = { onToggle(recipient.id) },
                        )
                    }
                }
                if (contacts.isNotEmpty()) {
                    section(contactsLabel)
                    items(contacts, key = { "c-${it.id.raw}" }) { recipient ->
                        AddRecipientRow(
                            recipient = recipient,
                            locked = recipient.alreadyShared(),
                            selected = recipient.id in selected,
                            onClick = { onToggle(recipient.id) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(MR.string.moments_create_selected_count, selected.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (isAdding) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(20.dp),
                    )
                }
                Button(
                    onClick = onConfirm,
                    enabled = selected.isNotEmpty() && !isAdding,
                ) {
                    Text(stringResource(MR.string.moments_add_people_confirm))
                }
            }
        }
    }
}

private fun LazyListScope.section(title: String) {
    item {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun AddRecipientRow(
    recipient: MomentsRecipient,
    locked: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !locked, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Render avatars the same way chat does: individuals resolve their
        // public profile image from the odinId via PublicAvatar; groups fall
        // back to chat's group glyph (mirrors ConversationAvatar's
        // GroupFallback). See ConversationAvatar.kt / PublicAvatar.kt.
        val avatarOptions = AvatarOptions(size = 40.dp)
        when (recipient) {
            is MomentsRecipient.Individual -> PublicAvatar(
                odinId = recipient.odinId,
                initials = recipient.avatarInitials,
                options = avatarOptions,
            )

            is MomentsRecipient.Group -> FallbackAvatar(
                initials = recipient.avatarInitials,
                options = avatarOptions,
                imageVector = Icons.Default.People,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = recipient.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = if (locked) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
            when {
                locked -> Text(
                    text = stringResource(MR.string.moments_add_people_already_shared),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                recipient is MomentsRecipient.Group -> Text(
                    text = stringResource(
                        MR.string.number_of_members,
                        recipient.memberCount.toString(),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Locked rows show a static, dimmed check (already in the audience);
        // selectable rows show the interactive selection indicator.
        SelectionIndicator(selected = locked || selected, dimmed = locked)
    }
}

@Composable
private fun SelectionIndicator(selected: Boolean, dimmed: Boolean) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(
                when {
                    dimmed -> MaterialTheme.colorScheme.surfaceContainerHighest
                    selected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
