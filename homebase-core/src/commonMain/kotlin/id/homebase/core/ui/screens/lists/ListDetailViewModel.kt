package id.homebase.core.ui.screens.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.lists.model.ListSortKeys
import id.homebase.core.lists.services.ListItemSenderService
import id.homebase.core.lists.services.ListService
import id.homebase.core.lists.services.ListStream
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // A reorder write merges back asynchronously (optimistic write -> BatchReceived -> re-sort), so
    // two quick drags would otherwise both resolve neighbour keys from the SAME stale stream and
    // could collide on an identical between-key. Serialize reorders and remember each issued key
    // until the stream confirms it, so a follow-up drag resolves neighbours against the in-flight
    // key. Mirrors the addMutex + lastIssuedSortKey hardening in ListItemSenderService.addItem.
    private val reorderMutex = Mutex()
    private val pendingSortKeys = mutableMapOf<Uuid, String>()

    init {
        // Drop a pending key once the stream confirms it (the item's sortKey == the key we issued).
        // Collect the raw stream (not uiState) so this internal subscriber doesn't change uiState's
        // WhileSubscribed sharing.
        viewModelScope.launch {
            listStream.itemsByList.collect { byList ->
                if (pendingSortKeys.isEmpty()) return@collect
                byList[listId].orEmpty().forEach { rec ->
                    if (pendingSortKeys[rec.itemId] == rec.item.sortKey) pendingSortKeys.remove(rec.itemId)
                }
            }
        }
    }

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
     * moved item in the final visual order (null at an edge). Resolves their sortKeys (preferring an
     * in-flight pending key over the possibly-stale stream), skips a no-op move, and issues ONE
     * reorder write under [reorderMutex] so rapid successive drags can't collide on a shared key.
     */
    fun reorder(itemId: Uuid, aboveItemId: Uuid?, belowItemId: Uuid?) {
        viewModelScope.launch {
            reorderMutex.withLock {
                val items = uiState.value.items
                fun keyOf(id: Uuid): String? = pendingSortKeys[id] ?: items.find { it.itemId == id }?.sortKey
                val aboveKey = aboveItemId?.let { keyOf(it) }
                val belowKey = belowItemId?.let { keyOf(it) }
                val currentKey = keyOf(itemId)
                // No-op: the item already sits strictly between these neighbours (e.g. dropped in
                // place, or the only item in the list) — don't churn an optimistic write + transit.
                if (currentKey != null &&
                    (aboveKey == null || currentKey > aboveKey) &&
                    (belowKey == null || currentKey < belowKey)
                ) {
                    return@withLock
                }
                val newKey = computeReorderKey(aboveKey, belowKey)
                pendingSortKeys[itemId] = newKey
                itemSender.reorderItem(listId, itemId, newKey)
            }
        }
    }

    fun renameList(newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { listService.renameList(listId, newTitle = trimmed) }
    }

    fun deleteList() {
        viewModelScope.launch { listService.deleteList(listId) }
    }
}
