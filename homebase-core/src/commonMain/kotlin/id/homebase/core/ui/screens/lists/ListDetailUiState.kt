package id.homebase.core.ui.screens.lists

import kotlin.uuid.Uuid

data class ListDetailItem(
    val itemId: Uuid,
    val body: String,
    val checked: Boolean,
    val sortKey: String,
)

data class ListDetailUiState(
    val isLoading: Boolean = true,
    val exists: Boolean = false,
    val title: String = "",
    val items: List<ListDetailItem> = emptyList(),
)

sealed interface ListDetailEvent {
    /** The list was deleted (here or by a collaborator) — the screen should pop. */
    data object ListDeleted : ListDetailEvent
}
