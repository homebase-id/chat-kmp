package id.homebase.core.settings

import com.russhwolf.settings.Settings
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

/**
 * Minimal in-memory [Settings] for unit tests. Pulling the official
 * `multiplatform-settings-test` artifact for one test file would add a
 * dependency just to get `MapSettings`; this is simpler.
 */
private class InMemorySettings(seed: Map<String, Any> = emptyMap()) : Settings {
    private val backing: MutableMap<String, Any> = seed.toMutableMap()
    override val keys: Set<String> get() = backing.keys.toSet()
    override val size: Int get() = backing.size
    override fun clear() = backing.clear()
    override fun remove(key: String) { backing.remove(key) }
    override fun hasKey(key: String): Boolean = backing.containsKey(key)
    override fun putInt(key: String, value: Int) { backing[key] = value }
    override fun getInt(key: String, defaultValue: Int): Int = (backing[key] as? Int) ?: defaultValue
    override fun getIntOrNull(key: String): Int? = backing[key] as? Int
    override fun putLong(key: String, value: Long) { backing[key] = value }
    override fun getLong(key: String, defaultValue: Long): Long = (backing[key] as? Long) ?: defaultValue
    override fun getLongOrNull(key: String): Long? = backing[key] as? Long
    override fun putString(key: String, value: String) { backing[key] = value }
    override fun getString(key: String, defaultValue: String): String = (backing[key] as? String) ?: defaultValue
    override fun getStringOrNull(key: String): String? = backing[key] as? String
    override fun putFloat(key: String, value: Float) { backing[key] = value }
    override fun getFloat(key: String, defaultValue: Float): Float = (backing[key] as? Float) ?: defaultValue
    override fun getFloatOrNull(key: String): Float? = backing[key] as? Float
    override fun putDouble(key: String, value: Double) { backing[key] = value }
    override fun getDouble(key: String, defaultValue: Double): Double = (backing[key] as? Double) ?: defaultValue
    override fun getDoubleOrNull(key: String): Double? = backing[key] as? Double
    override fun putBoolean(key: String, value: Boolean) { backing[key] = value }
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = (backing[key] as? Boolean) ?: defaultValue
    override fun getBooleanOrNull(key: String): Boolean? = backing[key] as? Boolean
}
