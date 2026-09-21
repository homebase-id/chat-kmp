package id.homebase.core.ui.screens.card

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class CardProtocolTest {

    @Test
    fun parsesEveryEventType() {
        assertEquals(CardEvent.Loaded, parseCardEvent("""{"type":"loaded"}"""))
        assertEquals(CardEvent.Ready("board", 123), parseCardEvent("""{"type":"ready","layout":"board","ms":123}"""))
        assertEquals(CardEvent.Link("https://a.b/c"), parseCardEvent("""{"type":"link","href":"https://a.b/c"}"""))
        assertEquals(
            CardEvent.Png("iVBORw0KGgo=", 1080, 2338),
            parseCardEvent("""{"type":"png","base64":"iVBORw0KGgo=","width":1080,"height":2338}"""),
        )
        assertEquals(CardEvent.Error("boom"), parseCardEvent("""{"type":"error","message":"boom"}"""))
    }

    @Test
    fun readyAcceptsFractionalMilliseconds() {
        assertEquals(CardEvent.Ready("poster", 124), parseCardEvent("""{"type":"ready","layout":"poster","ms":123.6}"""))
    }

    @Test
    fun ignoresUnknownFields() {
        assertEquals(CardEvent.Loaded, parseCardEvent("""{"type":"loaded","version":2}"""))
    }

    @Test
    fun garbageIsNullNotAThrow() {
        listOf(
            "",
            "not json",
            "{",
            "[]",
            "\"loaded\"",
            "null",
            "{}",
            """{"type":"nope"}""",
            """{"type":7}""",
            """{"type":"ready","layout":"board"}""",
            """{"type":"ready","layout":"board","ms":"123"}""",
            """{"type":"link"}""",
            """{"type":"png","base64":"x","width":1080}""",
            """{"type":"error"}""",
        ).forEach { assertNull(parseCardEvent(it), "expected null for: $it") }
    }

    @Test
    fun optionalFieldsAreOmittedButListsAreAlwaysSent() {
        val json = cardJson.encodeToString(
            CardPayload.serializer(),
            CardPayload(design = CardDesign.POSTER, data = CardData(odinId = "frodo.dotyou.cloud")),
        )
        val data = cardJson.parseToJsonElement(json).jsonObject.getValue("data") as JsonObject
        assertEquals(setOf("odinId", "links", "socials"), data.keys)
        assertFalse("null" in json)
    }

    @Test
    fun renderScriptCallsThePageApi() {
        val script = renderScript(CardPayload(design = CardDesign.COLLAGE, data = CardData(odinId = "a.b")))
        assertTrue(script.startsWith("window.homebaseCard.render({"))
        assertTrue(script.endsWith("})"))
    }
}
