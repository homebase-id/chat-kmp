package id.homebase.core.ui.screens.lists

import id.homebase.core.lists.model.ListDefinition
import id.homebase.core.lists.model.ListItem
import id.homebase.core.lists.services.ListItemRecord
import id.homebase.core.lists.services.ListRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class ListOverviewViewModelTest {

    private fun rec(title: String): ListRecord {
        val id = Uuid.random()
        return ListRecord(id, Uuid.random(), null, null, ListDefinition(title = title))
    }

    private fun item(listId: Uuid, checked: Boolean): ListItemRecord =
        ListItemRecord(Uuid.random(), listId, Uuid.random(), null, null, ListItem(body = "x", checked = checked, sortKey = "n"))

    @Test
    fun rows_carry_total_and_checked_counts() {
        val a = rec("Groceries")
        val b = rec("Chores")
        val itemsByList = mapOf(
            a.listId to listOf(item(a.listId, true), item(a.listId, false), item(a.listId, true)),
            b.listId to emptyList(),
        )
        val rows = mapOverviewRows(listOf(a, b), itemsByList)
        assertEquals(2, rows.size)
        val groceries = rows.first { it.title == "Groceries" }
        assertEquals(3, groceries.totalCount)
        assertEquals(2, groceries.checkedCount)
        val chores = rows.first { it.title == "Chores" }
        assertEquals(0, chores.totalCount)
        assertEquals(0, chores.checkedCount)
    }
}
