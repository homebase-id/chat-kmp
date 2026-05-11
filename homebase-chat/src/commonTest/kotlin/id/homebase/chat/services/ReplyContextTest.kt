package id.homebase.chat.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ReplyContextTest {

    @Test
    fun null_input_returns_null() {
        // No context attached at all — pre-feature senders, plain replies.
        assertNull(ReplyContext.fromJson(null))
    }

    @Test
    fun event_kind_parses_to_typed_event() {
        val json = buildJsonObject {
            put("kind", "event")
            put("startUtcMs", 1747094400000L)
        }
        val ctx = ReplyContext.fromJson(json)
        assertIs<ReplyContext.Event>(ctx)
        assertEquals(1747094400000L, ctx.startUtcMs)
    }

    @Test
    fun event_kind_missing_start_falls_to_unknown() {
        val json = buildJsonObject { put("kind", "event") }
        // Malformed event context shouldn't crash the renderer — degrade to Unknown.
        assertEquals(ReplyContext.Unknown, ReplyContext.fromJson(json))
    }

    @Test
    fun unknown_kind_falls_to_unknown() {
        // A future "doodle" or "poll" kind on an old client falls through here.
        val json = buildJsonObject { put("kind", "doodle") }
        assertEquals(ReplyContext.Unknown, ReplyContext.fromJson(json))
    }

    @Test
    fun missing_kind_field_falls_to_unknown() {
        val json = buildJsonObject { put("startUtcMs", 1747094400000L) }
        assertEquals(ReplyContext.Unknown, ReplyContext.fromJson(json))
    }

    @Test
    fun non_object_input_returns_null() {
        // A bare primitive isn't a valid context — treat as "no context".
        assertNull(ReplyContext.fromJson(JsonPrimitive("event")))
    }

    @Test
    fun event_builder_round_trips() {
        val built: JsonObject = ReplyContext.event(1747094400000L)
        val parsed = ReplyContext.fromJson(built)
        assertEquals(ReplyContext.Event(1747094400000L), parsed)
    }

    @Test
    fun event_kind_ignores_unknown_fields_for_forward_compat() {
        // If a future client adds optional fields to the event context (e.g.
        // durationMin, colorHint, recurrenceRule), this client must keep
        // parsing the fields it knows and ignore the rest — that's how
        // additive-only changes ship without coordination. Pin the
        // behaviour so a future refactor can't quietly tighten the parser.
        val json = buildJsonObject {
            put("kind", "event")
            put("startUtcMs", 1747094400000L)
            put("durationMin", 60)
            put("colorHint", "#FF0000")
            put("organizerOdinId", "frodo.baggins.demo.rocks")
        }
        val ctx = ReplyContext.fromJson(json)
        assertIs<ReplyContext.Event>(ctx)
        assertEquals(1747094400000L, ctx.startUtcMs)
    }
}
