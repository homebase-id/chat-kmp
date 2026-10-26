package id.homebase.core.settings

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CreateSettingsContractTest {

    @Test
    fun valuesAreSharedAcrossInstancesAndRemovable() {
        val key = "contract_test_${Random.nextLong().toULong()}"
        val first = createSettings()
        try {
            assertNull(first.getStringOrNull(key))
            assertEquals("fallback", first.getString(key, "fallback"))
            first.putString(key, "stored")
            assertEquals("stored", createSettings().getString(key, "fallback"))
            first.putInt("${key}_int", 7)
            assertEquals(7, createSettings().getInt("${key}_int", 0))
        } finally {
            first.remove(key)
            first.remove("${key}_int")
        }
        assertNull(createSettings().getStringOrNull(key))
    }
}
