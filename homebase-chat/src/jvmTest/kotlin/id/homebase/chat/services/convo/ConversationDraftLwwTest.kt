package id.homebase.chat.services.convo

import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure last-write-wins semantics for the per-conversation composer draft (#1122):
 * the stamp path routes through [withDraftLww], and the draft round-trips through
 * the owner-private [ConversationLocalAppDataJson] carrier.
 */
class ConversationDraftLwwTest {

    @Test
    fun newerDraftWins() {
        val base = ConversationLocalAppDataJson(draft = "old", draftUpdatedAt = UnixTimeUtc(100))
        val next = base.withDraftLww("new", UnixTimeUtc(200))
        assertEquals("new", next.draft)
        assertEquals(200, next.draftUpdatedAt?.milliseconds)
    }

    @Test
    fun olderDraftIsDropped() {
        val base = ConversationLocalAppDataJson(draft = "current", draftUpdatedAt = UnixTimeUtc(200))
        val next = base.withDraftLww("stale", UnixTimeUtc(100))
        assertEquals("current", next.draft)
        assertEquals(200, next.draftUpdatedAt?.milliseconds)
    }

    @Test
    fun equalTimestampOverwrites() {
        val base = ConversationLocalAppDataJson(draft = "a", draftUpdatedAt = UnixTimeUtc(150))
        val next = base.withDraftLww("b", UnixTimeUtc(150))
        assertEquals("b", next.draft)
    }

    @Test
    fun blankDraftClearsToNull() {
        val base = ConversationLocalAppDataJson(draft = "typing", draftUpdatedAt = UnixTimeUtc(100))
        val cleared = base.withDraftLww("", UnixTimeUtc(300))
        assertNull(cleared.draft)
        assertEquals(300, cleared.draftUpdatedAt?.milliseconds)
    }

    @Test
    fun firstDraftStampsWhenNonePresent() {
        val base = ConversationLocalAppDataJson()
        val next = base.withDraftLww("hello", UnixTimeUtc(50))
        assertEquals("hello", next.draft)
        assertEquals(50, next.draftUpdatedAt?.milliseconds)
    }

    @Test
    fun preservesSiblingFields() {
        val base = ConversationLocalAppDataJson(
            lastReadTime = UnixTimeUtc(10),
            lastExitedAt = UnixTimeUtc(20),
            latestMessageTimestamp = UnixTimeUtc(30),
        )
        val next = base.withDraftLww("d", UnixTimeUtc(40))
        assertEquals(10, next.lastReadTime?.milliseconds)
        assertEquals(20, next.lastExitedAt?.milliseconds)
        assertEquals(30, next.latestMessageTimestamp?.milliseconds)
        assertEquals("d", next.draft)
    }

    @Test
    fun serializationRoundTrip() {
        val original = ConversationLocalAppDataJson(
            lastReadTime = UnixTimeUtc(111),
            draft = "unsent **markdown**",
            draftUpdatedAt = UnixTimeUtc(222),
        )
        val json = OdinSystemSerializer.serialize(original)
        val back = OdinSystemSerializer.deserialize<ConversationLocalAppDataJson>(json)
        assertEquals("unsent **markdown**", back.draft)
        assertEquals(222, back.draftUpdatedAt?.milliseconds)
        assertEquals(111, back.lastReadTime?.milliseconds)
    }

    @Test
    fun legacyJsonWithoutDraftDeserializesToNull() {
        // A pre-#1122 carrier (no draft fields) must still parse, draft absent.
        // UnixTimeUtc serializes as a raw millis Long (UnixTimeUtcSerializer).
        val legacy = """{"lastReadTime":5}"""
        val back = OdinSystemSerializer.deserialize<ConversationLocalAppDataJson>(legacy)
        assertNull(back.draft)
        assertNull(back.draftUpdatedAt)
        assertEquals(5, back.lastReadTime?.milliseconds)
    }
}
