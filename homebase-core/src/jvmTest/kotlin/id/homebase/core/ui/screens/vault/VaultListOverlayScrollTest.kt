@file:OptIn(ExperimentalUuidApi::class, ExperimentalTestApi::class)

package id.homebase.core.ui.screens.vault

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.api.client.eventbus.EventBus
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.core.ui.screens.vault.model.VaultSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class VaultListOverlayScrollTest {

    private val store = LocalAttachmentContextStore(EventBus(), CoroutineScope(SupervisorJob()))

    @Test
    fun theHoistedListStateKeepsItsPlaceWhileTheOverlayReplacesTheGrid() = runComposeUiTest {
        val listState = LazyListState()
        var overlayOpen by mutableStateOf(false)
        var sections by mutableStateOf(
            List(30) { VaultSection(Uuid.random(), Uuid.random(), "Section $it", it) },
        )
        setContent {
            MaterialTheme {
                AnimatedContent(targetState = overlayOpen) { open ->
                    if (open) {
                        Box(Modifier.fillMaxSize())
                    } else {
                        VaultContent(
                            sections = sections,
                            isLoading = false,
                            isSyncing = false,
                            localAttachmentStore = store,
                            onEntryClick = {},
                            onAddEntry = {},
                            onFilesDropped = { _, _ -> },
                            onMoveUp = {},
                            onMoveDown = {},
                            onRenameSection = {},
                            onDeleteSection = {},
                            onAddSection = {},
                            modifier = Modifier.fillMaxSize(),
                            lazyListState = listState,
                        )
                    }
                }
            }
        }

        onNode(hasScrollToIndexAction()).performScrollToIndex(10)
        runOnIdle { listState.dispatchRawDelta(40f) }
        waitForIdle()
        val topSection = sections[listState.firstVisibleItemIndex].sectionId
        val topOffset = listState.firstVisibleItemScrollOffset

        overlayOpen = true
        waitForIdle()
        // The list changes while the overlay is up, like a delete or an upload landing.
        sections = sections.drop(1)
        overlayOpen = false
        waitForIdle()

        assertEquals(topSection, sections[listState.firstVisibleItemIndex].sectionId)
        assertEquals(topOffset, listState.firstVisibleItemScrollOffset)
    }
}
