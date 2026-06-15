package id.homebase.core.ui.screens.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.lists.model.ListSortKeys
import id.homebase.core.lists.services.ListItemSenderService
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

/** Fractional key for an item dropped between [aboveSortKey] and [belowSortKey] (either may be null at an edge). */
internal fun computeReorderKey(aboveSortKey: String?, belowSortKey: String?): String =
    ListSortKeys.between(aboveSortKey, belowSortKey)

class ListDetailViewModel(
    private val listId: Uuid,
    private val listStream: ListStream,
    private val itemSender: ListItemSenderService,
    private val listService: ListService,
) : ViewModel() {

    val uiState: StateFlow<ListDetailUiState> =
        combine(listStream.lists, listStream.itemsByList) { data, itemsByList ->
            val record = data.lists.find { it.listId == listId }
            ListDetailUiState(
                isLoading = !data.dataReady,
                exists = record != null,
                title = record?.definition?.title ?: "",
                items = itemsByList[listId].orEmpty().map {
                    ListDetailItem(
                        itemId = it.itemId,
                        body = it.item.body,
                        checked = it.item.checked,
                        sortKey = it.item.sortKey,
                    )
                },
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ListDetailUiState(isLoading = true),
        )

    private val _events = MutableSharedFlow<ListDetailEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ListDetailEvent> = _events.asSharedFlow()

    fun addItem(body: String) {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { itemSender.addItem(listId, trimmed) }
    }

    fun setChecked(itemId: Uuid, checked: Boolean) {
        viewModelScope.launch { itemSender.setChecked(listId, itemId, checked) }
    }

    fun editItem(itemId: Uuid, newBody: String) {
        val trimmed = newBody.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { itemSender.editItem(listId, itemId, trimmed) }
    }

    fun deleteItem(itemId: Uuid) {
        viewModelScope.launch { itemSender.deleteItem(listId, itemId) }
    }

    /**
     * Persist a drag-drop. [aboveItemId]/[belowItemId] are the items now directly above/below the
     * moved item in the final visual order (null at an edge). Resolves their sortKeys against the
     * latest state and issues ONE reorder write.
     */
    fun reorder(itemId: Uuid, aboveItemId: Uuid?, belowItemId: Uuid?) {
        val items = uiState.value.items
        val aboveKey = aboveItemId?.let { id -> items.find { it.itemId == id }?.sortKey }
        val belowKey = belowItemId?.let { id -> items.find { it.itemId == id }?.sortKey }
        val newKey = computeReorderKey(aboveKey, belowKey)
        viewModelScope.launch { itemSender.reorderItem(listId, itemId, newKey) }
    }

    fun renameList(newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { listService.renameList(listId, newTitle = trimmed) }
    }

    fun deleteList() {
        viewModelScope.launch {
            listService.deleteList(listId)
            _events.tryEmit(ListDetailEvent.ListDeleted)
        }
    }
}
