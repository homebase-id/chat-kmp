package id.homebase.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Round-trip coverage for [UserPreferences.getConversationScrollAnchor] and
 * [UserPreferences.setConversationScrollAnchor]. Replaces the older
 * `(scrollIndex: Int, scrollOffset: Int)` shape — see CLAUDE.md / PR 2 for
 * why anchor-by-message-id beats index-by-position across sessions.
 */
class UserPreferencesScrollAnchorTest {

    private val convoId = "11111111-1111-1111-1111-111111111111"

    private fun prefs(seed: Map<String, Any> = emptyMap()): Pair<UserPreferences, InMemorySettings> {
        val backing = InMemorySettings(seed)
        return UserPreferences(backing) to backing
    }

    @Test
    fun missingAnchor_returnsNull() {
        val (prefs, _) = prefs()
        assertNull(prefs.getConversationScrollAnchor(convoId))
    }

    @Test
    fun setAnchor_thenGet_roundtrips() {
        val (prefs, _) = prefs()
        val anchor = Uuid.parse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        prefs.setConversationScrollAnchor(convoId, anchor)
        assertEquals(anchor, prefs.getConversationScrollAnchor(convoId))
    }

    @Test
    fun setAnchorNull_clearsStoredValue() {
        val (prefs, _) = prefs()
        val anchor = Uuid.random()
        prefs.setConversationScrollAnchor(convoId, anchor)
        prefs.setConversationScrollAnchor(convoId, null)
        assertNull(prefs.getConversationScrollAnchor(convoId))
    }

    @Test
    fun corruptStoredValue_returnsNullInsteadOfThrowing() {
        // Possible if a Settings backend ever stored a non-UUID string under
        // this key — fail soft so the caller falls back to the default
        // landing position rather than crashing on conversation open.
        val (prefs, _) = prefs(mapOf("conversationScrollAnchor-$convoId" to "not-a-uuid"))
        assertNull(prefs.getConversationScrollAnchor(convoId))
    }

    @Test
    fun anchorAndOffset_areKeyedIndependentlyPerConversation() {
        val (prefs, _) = prefs()
        val a = Uuid.random()
        val b = Uuid.random()
        prefs.setConversationScrollAnchor("convo-a", a)
        prefs.setConversationScrollAnchor("convo-b", b)
        prefs.setConversationScrollOffset("convo-a", 11)
        prefs.setConversationScrollOffset("convo-b", 22)

        assertEquals(a, prefs.getConversationScrollAnchor("convo-a"))
        assertEquals(b, prefs.getConversationScrollAnchor("convo-b"))
        assertEquals(11, prefs.getConversationScrollOffset("convo-a"))
        assertEquals(22, prefs.getConversationScrollOffset("convo-b"))
    }
}

