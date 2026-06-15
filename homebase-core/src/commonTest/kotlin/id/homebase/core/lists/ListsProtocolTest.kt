package id.homebase.core.lists

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListsProtocolTest {
    @Test
    fun `file types are the reserved values and distinct`() {
        assertEquals(9100, ListsProtocol.ListDefinitionFileType)
        assertEquals(9101, ListsProtocol.ListItemFileType)
        assertTrue(ListsProtocol.ListDefinitionFileType != ListsProtocol.ListItemFileType)
    }

    @Test
    fun `caps are sane and under the header budget`() {
        assertTrue(ListsProtocol.MaxTitleCodePoints in 1..200)
        assertTrue(ListsProtocol.MaxItemBodyCodePoints in 1..7000)
    }
}
