package id.homebase.core.ui.screens.vault

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.core.ui.screens.vault.components.VaultEmptyState
import id.homebase.core.ui.screens.vault.model.VaultEntry
import id.homebase.core.ui.screens.vault.model.VaultSection

@Composable
fun VaultContent(
    sections: List<VaultSection>,
    isLoading: Boolean,
    isSyncing: Boolean,
    localAttachmentStore: LocalAttachmentContextStore,
    onEntryClick: (VaultEntry) -> Unit,
    onAddEntry: (VaultSection) -> Unit,
    onMoveUp: (VaultSection) -> Unit,
    onMoveDown: (VaultSection) -> Unit,
    onRenameSection: (VaultSection) -> Unit,
    onDeleteSection: (VaultSection) -> Unit,
    onAddSection: () -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    when {
        (isLoading || isSyncing) && sections.isEmpty() -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        sections.isEmpty() -> {
            VaultEmptyState(
                onAddSection = onAddSection,
                modifier = modifier,
            )
        }

        else -> {
            LazyColumn(
                modifier = modifier,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(sections, key = { it.sectionId }) { section ->
                    VaultSectionCard(
                        section = section,
                        localAttachmentStore = localAttachmentStore,
                        onEntryClick = { onEntryClick(it) },
                        onAddEntry = { onAddEntry(section) },
                        onMoveUp = { onMoveUp(section) },
                        onMoveDown = { onMoveDown(section) },
                        onRenameSection = { onRenameSection(section) },
                        onDeleteSection = { onDeleteSection(section) },
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                }
            }
        }
    }
}
