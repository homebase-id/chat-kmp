package id.homebase.core.ui.screens.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.lists.services.ListItemRecord
import id.homebase.core.lists.services.ListRecord
import id.homebase.core.lists.services.ListService
import id.homebase.core.lists.services.ListStream
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/** Pure mapping: list records + their items -> overview rows. Extracted for unit testing. */
internal fun mapOverviewRows(
    lists: List<ListRecord>,
    itemsByList: Map<Uuid, List<ListItemRecord>>,
): List<ListOverviewRow> = lists.map { rec ->
    val items = itemsByList[rec.listId].orEmpty()
    ListOverviewRow(
        listId = rec.listId,
        title = rec.definition.title,
        totalCount = items.size,
        checkedCount = items.count { it.item.checked },
    )
}

class ListOverviewViewModel(
    private val listStream: ListStream,
    private val listService: ListService,
) : ViewModel() {

    val uiState: StateFlow<ListOverviewUiState> =
        combine(listStream.lists, listStream.itemsByList) { data, itemsByList ->
            ListOverviewUiState(
                isLoading = !data.dataReady,
                rows = mapOverviewRows(data.lists, itemsByList),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ListOverviewUiState(isLoading = true),
        )

    private val _events = MutableSharedFlow<ListOverviewEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ListOverviewEvent> = _events.asSharedFlow()

    fun createList(title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val listId = listService.createList(trimmed)
            _events.tryEmit(ListOverviewEvent.OpenList(listId))
        }
    }

    fun renameList(listId: Uuid, newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { listService.renameList(listId, newTitle = trimmed) }
    }

    fun deleteList(listId: Uuid) {
        viewModelScope.launch { listService.deleteList(listId) }
    }
}
