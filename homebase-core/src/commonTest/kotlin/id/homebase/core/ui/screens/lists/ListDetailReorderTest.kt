package id.homebase.core.ui.screens.lists

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ListDetailReorderTest {

    private fun item(sortKey: String) = ListDetailItem(Uuid.random(), "x", false, sortKey)

    @Test
    fun dropping_between_two_items_yields_a_key_strictly_between_them() {
        val items = listOf(item("a"), item("g"), item("z"))
        val moved = items[2] // "z"
        // Drop it between items[0] ("a") and items[1] ("g").
        val newKey = computeReorderKey(aboveSortKey = items[0].sortKey, belowSortKey = items[1].sortKey)
        assertTrue(newKey > "a" && newKey < "g", "expected a < $newKey < g")
    }

    @Test
    fun dropping_at_the_top_yields_a_key_below_the_first() {
        val newKey = computeReorderKey(aboveSortKey = null, belowSortKey = "g")
        assertTrue(newKey < "g", "expected $newKey < g")
    }

    @Test
    fun dropping_at_the_bottom_yields_a_key_above_the_last() {
        val newKey = computeReorderKey(aboveSortKey = "g", belowSortKey = null)
        assertTrue(newKey > "g", "expected $newKey > g")
    }
}
