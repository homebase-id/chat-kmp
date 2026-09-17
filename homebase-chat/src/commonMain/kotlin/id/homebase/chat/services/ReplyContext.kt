package id.homebase.chat.services

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Typed view of [ReplyPreview.context]. Each known reply kind has its
 * own variant; an unknown `kind` string falls through to [Unknown] so
 * a renderer that doesn't recognise a future shape can degrade
 * gracefully (default reply preview) rather than crash.
 *
 * Wire form is a small JsonObject with a "kind" string discriminator:
 *
 *     {"kind":"event","startUtcMs":1747094400000}
 *     {"kind":"dice","faces":[6,4,3]}              // future
 *     {"kind":"doodle","w":256,"h":256}            // future
 *
 * Adding a new kind:
 *   1. Add a const KIND_X here.
 *   2. Add a sealed subtype.
 *   3. Add a parser arm in [fromJson].
 *   4. Add a builder companion function (mirrors [event]).
 *   5. Wire the dispatch in InlineReplyPreview / ReplyPreviewBar.
 *
 * Extending an existing kind (additive-only):
 *   - You can add new optional fields to a kind's JSON shape at any
 *     time — older clients ignore unknown keys and keep parsing the
 *     fields they know (pinned by ReplyContextTest's forward-compat
 *     case). Pull the new fields out in [fromJson] and add them to the
 *     sealed subtype.
 *   - Do NOT remove or rename existing fields, and do NOT change their
 *     types. That breaks older parsers in the field. If you genuinely
 *     need to replace a field, ship the new field alongside the old
 *     and deprecate the old one over time.
 */
sealed interface ReplyContext {

    /** Event reply: chip renders the viewer-local month/day from `startUtcMs`. */
    data class Event(val startUtcMs: Long) : ReplyContext

    /** Reply context whose `kind` we don't recognise — render as a default reply preview. */
    data object Unknown : ReplyContext

    companion object {
        const val KIND_EVENT = "event"

        /**
         * Parse a wire-side [JsonElement] into a typed [ReplyContext].
         *
         * Returns `null` when no context was attached (pre-feature senders or
         * non-typed replies). Returns [Unknown] when the discriminator is
         * present but unrecognised — that lets renderers degrade gracefully.
         */
        fun fromJson(element: JsonElement?): ReplyContext? {
            val obj = element as? JsonObject ?: return null
            val kind = (obj["kind"] as? JsonPrimitive)?.content ?: return Unknown
            return when (kind) {
                KIND_EVENT -> {
                    val ts = (obj["startUtcMs"] as? JsonPrimitive)?.longOrNull
                    if (ts != null) Event(ts) else Unknown
                }
                else -> Unknown
            }
        }

        /** Build an Event-kind context for [ReplyPreview.context]. */
        fun event(startUtcMs: Long): JsonObject = buildJsonObject {
            put("kind", KIND_EVENT)
            put("startUtcMs", startUtcMs)
        }
    }
}
