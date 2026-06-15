package id.homebase.core.ui.screens.lists

import kotlin.uuid.Uuid

/** One row in the lists overview. [checkedCount]/[totalCount] drive the "3 / 7" progress label. */
data class ListOverviewRow(
    val listId: Uuid,
    val title: String,
    val totalCount: Int,
    val checkedCount: Int,
)

data class ListOverviewUiState(
    val isLoading: Boolean = true,
    val rows: List<ListOverviewRow> = emptyList(),
)

/** One-time navigation effects (e.g. open the list the user just created). */
sealed interface ListOverviewEvent {
    data class OpenList(val listId: Uuid) : ListOverviewEvent
}
