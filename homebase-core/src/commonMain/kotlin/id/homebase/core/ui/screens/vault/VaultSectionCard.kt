package id.homebase.core.ui.screens.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.core.ui.screens.vault.model.VaultEntry
import id.homebase.core.ui.screens.vault.model.VaultSection
import id.homebase.resources.MR
import id.homebase.resources.vault_entry_add
import id.homebase.resources.vault_section_delete
import id.homebase.resources.vault_section_empty_hint
import id.homebase.resources.vault_section_menu
import id.homebase.resources.vault_section_move_down
import id.homebase.resources.vault_section_move_up
import id.homebase.resources.vault_section_rename
import org.jetbrains.compose.resources.stringResource

@Composable
fun VaultSectionCard(
    section: VaultSection,
    localAttachmentStore: LocalAttachmentContextStore,
    onEntryClick: (VaultEntry) -> Unit,
    onAddEntry: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRenameSection: () -> Unit,
    onDeleteSection: () -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header row: title + add button + 3-dot menu
            SectionHeader(
                title = section.title,
                isFirst = section.isFirst,
                isLast = section.isLast,
                onAddEntry = onAddEntry,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onRenameSection = onRenameSection,
                onDeleteSection = onDeleteSection,
            )

            // Empty hint when no entries
            if (section.entries.isEmpty()) {
                Text(
                    text = stringResource(MR.string.vault_section_empty_hint, section.title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            // Entry cards row
            if (section.entries.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(end = 4.dp),
                ) {
                    items(section.entries, key = { it.uniqueId }) { entry ->
                        VaultEntryCard(
                            file = entry,
                            sectionTitle = section.title,
                            localAttachmentStore = localAttachmentStore,
                            onClick = { onEntryClick(entry) },
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    isFirst: Boolean,
    isLast: Boolean,
    onAddEntry: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRenameSection: () -> Unit,
    onDeleteSection: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )

        IconButton(onClick = onAddEntry) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(MR.string.vault_entry_add),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(MR.string.vault_section_menu),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(MR.string.vault_section_move_up)) },
                    leadingIcon = { Icon(Icons.Outlined.ArrowUpward, contentDescription = null) },
                    enabled = !isFirst,
                    onClick = {
                        menuExpanded = false
                        onMoveUp()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(MR.string.vault_section_move_down)) },
                    leadingIcon = { Icon(Icons.Outlined.ArrowDownward, contentDescription = null) },
                    enabled = !isLast,
                    onClick = {
                        menuExpanded = false
                        onMoveDown()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(MR.string.vault_section_rename)) },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onRenameSection()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(MR.string.vault_section_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onDeleteSection()
                    },
                )
            }
        }
    }
}
