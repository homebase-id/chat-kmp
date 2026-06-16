package id.homebase.core.lists.model

import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListDefinitionTest {
    @Test
    fun `valid definition round-trips through OdinSystemSerializer`() {
        val def = ListDefinition(
            title = "Groceries",
            members = listOf(OdinId("alice.example.com"), OdinId("bob.example.com")),
            colorOrEmoji = "🛒",
        )
        val json = OdinSystemSerializer.serialize(def)
        val back = OdinSystemSerializer.deserialize<ListDefinition>(json)
        assertEquals(def, back)
        assertTrue(back.isValid())
        assertTrue(json.contains("alice.example.com"))
    }

    @Test
    fun `blank title is invalid`() {
        assertFalse(ListDefinition(title = "  ").isValid())
    }

    @Test
    fun `over-long title is invalid`() {
        val long = "x".repeat(id.homebase.core.lists.ListsProtocol.MaxTitleCodePoints + 1)
        assertFalse(ListDefinition(title = long).isValid())
    }

    @Test
    fun `unknown json keys are ignored (forward-compat)`() {
        val json = """{"title":"T","members":[],"futureField":42,"schemaVersion":1}"""
        val def = OdinSystemSerializer.deserialize<ListDefinition>(json)
        assertEquals("T", def.title)
    }
}
