package id.homebase.chat.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageAppDataLegacyRichTextTest {

    @Test
    fun json_array_body_produces_markdown() {
        val body = Json.parseToJsonElement(
            """[{"type":"p","children":[{"text":"hello ","bold":true},{"text":"world"}]}]"""
        )
        val data = MessageAppData(message = body)
        assertEquals("**hello** world", data.getMessage())
        assertTrue(data.bodyIsMarkdown)
    }

    @Test
    fun primitive_with_null_version_stays_plain() {
        val data = MessageAppData(message = JsonPrimitive("*raw* text"))
        assertEquals("*raw* text", data.getMessage())
        assertFalse(data.bodyIsMarkdown)
    }

    @Test
    fun version_one_is_markdown() {
        assertTrue(MessageAppData(message = JsonPrimitive("**x**"), version = 1).bodyIsMarkdown)
    }

    @Test
    fun version_zero_json_array_is_markdown_like_get_message() {
        val data = MessageAppData(message = Json.parseToJsonElement("""[{"type":"p","children":[{"text":"x","bold":true}]}]"""), version = 0)
        assertEquals("**x**", data.getMessage())
        assertTrue(data.bodyIsMarkdown)
    }
}
