package id.homebase.core.lists.model

import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListItemTest {
    @Test
    fun `valid item round-trips`() {
        val item = ListItem(body = "Buy **milk**", checked = false, sortKey = "n")
        val json = OdinSystemSerializer.serialize(item)
        assertEquals(item, OdinSystemSerializer.deserialize<ListItem>(json))
        assertTrue(item.isValid())
    }

    @Test
    fun `checked item may record who checked it`() {
        val item = ListItem(body = "x", checked = true, checkedByOdinId = OdinId("mom.example.com"), sortKey = "n")
        assertTrue(item.isValid())
        assertEquals("mom.example.com", item.checkedByOdinId?.domainName)
    }

    @Test
    fun `checkedBy without checked is invalid`() {
        assertFalse(ListItem(body = "x", checked = false, checkedByOdinId = OdinId("a.example.com"), sortKey = "n").isValid())
    }

    @Test
    fun `blank body or blank sortKey is invalid`() {
        assertFalse(ListItem(body = "   ", sortKey = "n").isValid())
        assertFalse(ListItem(body = "x", sortKey = "").isValid())
    }
}
