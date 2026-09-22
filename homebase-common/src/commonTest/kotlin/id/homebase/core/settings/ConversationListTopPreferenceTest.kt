package id.homebase.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * The marker only earns its keep by surviving process death, so the stored form has to survive a
 * fresh [UserPreferences] over the same backing store.
 */
class ConversationListTopPreferenceTest {

    @Test
    fun unsetKeyReadsNull() {
        assertNull(UserPreferences(InMemorySettings()).conversationListTopId)
    }

    @Test
    fun roundTripsAcrossInstances() {
        val settings = InMemorySettings()
        val top = Uuid.random()

        UserPreferences(settings).conversationListTopId = top

        assertEquals(top, UserPreferences(settings).conversationListTopId)
    }

    @Test
    fun nullClearsTheStoredMarker() {
        val settings = InMemorySettings()
        val prefs = UserPreferences(settings)
        prefs.conversationListTopId = Uuid.random()

        prefs.conversationListTopId = null

        assertNull(prefs.conversationListTopId)
        assertEquals(0, settings.size)
    }

    @Test
    fun anUnparseableStoredValueReadsNull() {
        val settings = InMemorySettings()
        settings.putString("conversationListTopId", "not-a-uuid")

        assertNull(UserPreferences(settings).conversationListTopId)
    }
}
