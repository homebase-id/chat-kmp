package id.homebase.core.lists.services

import id.homebase.core.lists.model.ListDefinition
import id.homebase.core.lists.model.ListItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class ListStreamParsingTest {
    private fun listId() = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private fun itemId() = Uuid.parse("22222222-2222-2222-2222-222222222222")

    @Test
    fun `definition + items group under one list, items sorted by sortKey`() {
        val state = ListStreamState()
        state.upsertDefinition(ListRecord(listId(), Uuid.random(), null, null, ListDefinition("Groceries")))
        state.upsertItem(ListItemRecord(Uuid.random(), listId(), Uuid.random(), null, null, ListItem("milk", sortKey = "n")))
        state.upsertItem(ListItemRecord(Uuid.random(), listId(), Uuid.random(), null, null, ListItem("bread", sortKey = "g")))
        val lists = state.lists()
        assertEquals(1, lists.size)
        assertEquals("Groceries", lists.single().definition.title)
        assertEquals(listOf("bread", "milk"), state.itemsByList()[listId()]!!.map { it.item.body })
    }

    @Test
    fun `removing a definition drops the list`() {
        val state = ListStreamState()
        state.upsertDefinition(ListRecord(listId(), Uuid.random(), null, null, ListDefinition("X")))
        state.removeDefinition(listId())
        assertEquals(0, state.lists().size)
    }

    @Test
    fun `removing an item drops it from its list`() {
        val state = ListStreamState()
        state.upsertDefinition(ListRecord(listId(), Uuid.random(), null, null, ListDefinition("X")))
        state.upsertItem(ListItemRecord(itemId(), listId(), Uuid.random(), null, null, ListItem("a", sortKey = "n")))
        state.removeItem(itemId())
        assertNull(state.itemsByList()[listId()]?.firstOrNull())
    }

    @Test
    fun `clear resets all state`() {
        val state = ListStreamState()
        state.upsertDefinition(ListRecord(listId(), Uuid.random(), null, null, ListDefinition("X")))
        state.clear()
        assertEquals(0, state.lists().size)
    }
}
