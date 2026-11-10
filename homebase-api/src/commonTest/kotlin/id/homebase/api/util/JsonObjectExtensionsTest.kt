package id.homebase.api.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JsonObjectExtensionsTest {
    private val o: JsonObject =
        Json.parseToJsonElement(
            """{"s":"1","n":1,"f":1.5,"b":true,"bs":"true","nul":null,"w":1.0,"big":3000000000}""",
        ).jsonObject

    @Test
    fun stringOnlyFromJsonStrings() {
        assertEquals("1", o.strictString("s"))
        assertEquals("true", o.strictString("bs"))
        assertNull(o.strictString("n"))
        assertNull(o.strictString("b"))
        assertNull(o.strictString("nul"))
        assertNull(o.strictString("missing"))
    }

    @Test
    fun numbersAndBooleansNeverFromStrings() {
        assertNull(o.strictInt("s"))
        assertNull(o.strictLong("s"))
        assertNull(o.strictDouble("s"))
        assertNull(o.strictBoolean("bs"))
    }

    @Test
    fun numbersAndBooleansFromLiterals() {
        assertEquals(1, o.strictInt("n"))
        assertEquals(1.5, o.strictDouble("f"))
        assertEquals(true, o.strictBoolean("b"))
        assertEquals(3000000000L, o.strictLong("big"))
        assertNull(o.strictInt("big"))
        assertNull(o.strictInt("f"))
        assertNull(o.strictInt("w"))
        assertNull(o.strictBoolean("n"))
        assertNull(o.strictInt("b"))
    }
}
