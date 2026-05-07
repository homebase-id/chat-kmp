package id.homebase.core.ui.screens.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.moments_create_chat_group_label
import id.homebase.resources.moments_create_continue
import id.homebase.resources.moments_create_new_group
import id.homebase.resources.moments_create_one_off
import id.homebase.resources.moments_create_search_hint
import id.homebase.resources.moments_create_section_contacts
import id.homebase.resources.moments_create_section_groups
import id.homebase.resources.moments_create_section_quick
import id.homebase.resources.moments_create_section_recent
import id.homebase.resources.moments_create_selected_count
import id.homebase.resources.moments_create_title
import org.jetbrains.compose.resources.stringResource

/**
 * Page 3 — Create / Audience Selector.
 *
 * Skeleton only: hardcoded sample groups + contacts, multi-select state in
 * memory, Next button gated on a non-empty selection. Tapping Next is a stub
 * — the composer (camera roll picker + description + reactions toggle) is a
 * follow-up step.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentCreateScreen(
    onNavigateBack: () -> Unit,
    onAudienceSelected: (recipientIds: Set<String>) -> Unit = {},
    onCreateNewGroup: () -> Unit = {},
    onOneOff: () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }

    val recents = remember { sampleRecents() }
    val groups = remember { sampleGroups() }
    val contacts = remember { sampleContacts() }

    // stringResource() is @Composable — resolve the labels outside the
    // LazyColumn lambda (LazyListScope is not @Composable).
    val recentSectionLabel = stringResource(MR.string.moments_create_section_recent)
    val quickSectionLabel = stringResource(MR.string.moments_create_section_quick)
    val groupsSectionLabel = stringResource(MR.string.moments_create_section_groups)
    val contactsSectionLabel = stringResource(MR.string.moments_create_section_contacts)
    val searchHintLabel = stringResource(MR.string.moments_create_search_hint)
    val newGroupLabel = stringResource(MR.string.moments_create_new_group)
    val oneOffLabel = stringResource(MR.string.moments_create_one_off)

    val toggle: (String) -> Unit = { id ->
        selected = if (id in selected) selected - id else selected + id
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.moments_create_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            CreateBottomBar(
                selectedCount = selected.size,
                enabled = selected.isNotEmpty(),
                onContinue = { onAudienceSelected(selected) },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(searchHintLabel) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // Most-Recently-Used — at the top for fast re-use.
            section(recentSectionLabel)
            items(recents, key = { "recent-${it.id}" }) { recipient ->
                RecipientRow(
                    recipient = recipient,
                    selected = recipient.id in selected,
                    onClick = { toggle(recipient.id) },
                )
            }

            // Quick options — non-recipient actions that go elsewhere.
            section(quickSectionLabel)
            item {
                QuickActionRow(
                    icon = Icons.Outlined.GroupAdd,
                    label = newGroupLabel,
                    onClick = onCreateNewGroup,
                )
            }
            item {
                QuickActionRow(
                    icon = Icons.Outlined.PersonAdd,
                    label = oneOffLabel,
                    onClick = onOneOff,
                )
            }

            section(groupsSectionLabel)
            items(groups, key = { "group-${it.id}" }) { recipient ->
                RecipientRow(
                    recipient = recipient,
                    selected = recipient.id in selected,
                    onClick = { toggle(recipient.id) },
                )
            }

            section(contactsSectionLabel)
            items(contacts, key = { "contact-${it.id}" }) { recipient ->
                RecipientRow(
                    recipient = recipient,
                    selected = recipient.id in selected,
                    onClick = { toggle(recipient.id) },
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: String) {
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
private fun RecipientRow(
    recipient: MomentRecipient,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RecipientAvatar(recipient)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = recipient.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (recipient is MomentRecipient.Group && recipient.isChatGroup) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = stringResource(MR.string.moments_create_chat_group_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        SelectionIndicator(selected = selected)
    }
}

@Composable
private fun RecipientAvatar(recipient: MomentRecipient) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = when (recipient) {
                is MomentRecipient.Group -> Icons.Outlined.Group
                is MomentRecipient.Contact -> Icons.Outlined.Person
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SelectionIndicator(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun QuickActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CreateBottomBar(
    selectedCount: Int,
    enabled: Boolean,
    onContinue: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(MR.string.moments_create_selected_count, selectedCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onContinue,
                    enabled = enabled,
                ) {
                    Text(stringResource(MR.string.moments_create_continue))
                }
            }
        }
    }
}

private sealed interface MomentRecipient {
    val id: String
    val name: String

    data class Group(
        override val id: String,
        override val name: String,
        val isChatGroup: Boolean,
    ) : MomentRecipient

    data class Contact(
        override val id: String,
        override val name: String,
    ) : MomentRecipient
}

private fun sampleRecents(): List<MomentRecipient> = listOf(
    MomentRecipient.Group("g-family", "Family", isChatGroup = true),
    MomentRecipient.Contact("c-alice", "Alice"),
    MomentRecipient.Group("g-photo", "Photography Friends", isChatGroup = false),
)

private fun sampleGroups(): List<MomentRecipient> = listOf(
    MomentRecipient.Group("g-family", "Family", isChatGroup = true),
    MomentRecipient.Group("g-photo", "Photography Friends", isChatGroup = false),
    MomentRecipient.Group("g-coworkers", "Coworkers", isChatGroup = true),
    MomentRecipient.Group("g-hiking", "Hiking Crew", isChatGroup = false),
)

private fun sampleContacts(): List<MomentRecipient> = listOf(
    MomentRecipient.Contact("c-alice", "Alice"),
    MomentRecipient.Contact("c-bob", "Bob"),
    MomentRecipient.Contact("c-charlie", "Charlie"),
    MomentRecipient.Contact("c-dana", "Dana"),
    MomentRecipient.Contact("c-eli", "Eli"),
)
