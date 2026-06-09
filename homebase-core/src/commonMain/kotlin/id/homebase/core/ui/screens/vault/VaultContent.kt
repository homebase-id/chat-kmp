@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.vault

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.core.ui.screens.vault.components.VaultEmptyState
import id.homebase.core.ui.screens.vault.model.VaultEntry
import id.homebase.core.ui.screens.vault.model.VaultSection
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
    lazyListState: LazyListState = rememberLazyListState(),
    // Per-section horizontal scroll states, keyed by sectionId and owned by the
    // caller (VaultScreen) so they outlive this composable being torn down when
    // the full-screen overlay opens/closes. Default is a throwaway map for
    // previews/tests, where surviving the overlay swap is irrelevant.
    sectionRowStates: MutableMap<Uuid, LazyListState> = mutableMapOf(),
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
                state = lazyListState,
                modifier = modifier,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(sections, key = { it.sectionId }) { section ->
                    // getOrPut on the hoisted map gives each section a stable row
                    // state that persists across the overlay open/close. Created
                    // lazily the first time a section is composed.
                    val rowState = sectionRowStates.getOrPut(section.sectionId) { LazyListState() }
                    VaultSectionCard(
                        section = section,
                        localAttachmentStore = localAttachmentStore,
                        rowState = rowState,
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
