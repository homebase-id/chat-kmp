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
        assertEquals(CardEvent.Painted, parseCardEvent("""{"type":"hostPainted"}"""))
        assertEquals(
            CardEvent.Edges(0xFF1F4E8C.toInt(), null),
            parseCardEvent("""{"type":"hostEdges","top":"rgb(31, 78, 140)","bottom":null}"""),
        )
    }

    @Test
    fun cssColoursParseOnlyWhenOpaque() {
        assertEquals(0xFFF3EADB.toInt(), parseCssColor("rgb(243, 234, 219)"))
        assertEquals(0xFF000000.toInt(), parseCssColor("rgb(0, 0, 0)"))
        assertEquals(0xFF0E1013.toInt(), parseCssColor("rgba(14, 16, 19, 1)"))
        assertNull(parseCssColor("rgba(0, 0, 0, 0)"))
        assertNull(parseCssColor("rgba(20, 18, 15, 0.5)"))
        assertNull(parseCssColor("transparent"))
        assertNull(parseCssColor("color(display-p3 1 0 0)"))
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
        assertEquals(setOf("odinId", "links", "socials", "posts"), data.keys)
        assertFalse("null" in json)
    }

    @Test
    fun aPostEncodesToTheContractFieldNames() {
        val post = CardPost(
            id = "f1",
            href = "https://frodo.dotyou.cloud/posts/public-posts/there-and-back",
            date = 1_718_000_000_000,
            title = "There and back",
            excerpt = "A hobbit's tale",
            minutes = 2.4,
            type = "article",
            image = CardImage("data:image/jpeg;base64,AAAA"),
        )
        val json = cardJson.encodeToString(CardPost.serializer(), post)
        val fields = cardJson.parseToJsonElement(json).jsonObject
        assertEquals(setOf("id", "href", "date", "title", "excerpt", "minutes", "type", "image"), fields.keys)
        assertEquals(
            setOf("id", "href", "date"),
            cardJson.parseToJsonElement(cardJson.encodeToString(CardPost.serializer(), CardPost("f1", "https://a.b/posts/x/y", 1)))
                .jsonObject.keys,
        )
    }

    @Test
    fun commandsCallThePageApi() {
        val script = CardCommand.Render(CardPayload(design = CardDesign.COLLAGE, data = CardData(odinId = "a.b"))).script()
        assertTrue(script.startsWith("window.homebaseCard.render({"))
        assertTrue(script.endsWith("})"))
        assertEquals("window.homebaseCard.exportPng()", CardCommand.ExportPng.script())
    }
}
