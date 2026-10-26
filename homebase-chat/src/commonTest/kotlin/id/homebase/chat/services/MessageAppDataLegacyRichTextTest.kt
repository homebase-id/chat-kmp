package id.homebase.chat.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageAppDataLegacyRichTextTest {

    @Test
    fun json_array_body_produces_markdown() {
        val body = Json.parseToJsonElement(
            """[{"type":"p","children":[{"text":"hello ","bold":true},{"text":"world"}]}]"""
        )
        val data = MessageAppData(message = body)
        assertEquals("**hello** world", data.getMessage())
    }

    @Test
    fun primitive_with_null_version_stays_plain() {
        val data = MessageAppData(message = JsonPrimitive("*raw* text"))
        assertEquals("*raw* text", data.getMessage())
    }

    @Test
    fun version_zero_json_array_produces_markdown() {
        val data = MessageAppData(message = Json.parseToJsonElement("""[{"type":"p","children":[{"text":"x","bold":true}]}]"""), version = 0)
        assertEquals("**x**", data.getMessage())
    }
}
