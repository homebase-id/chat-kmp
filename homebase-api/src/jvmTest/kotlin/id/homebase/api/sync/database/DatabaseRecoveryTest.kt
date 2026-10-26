package id.homebase.api.sync.database

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DatabaseRecoveryTest {

    @Test
    fun `an unavailable database is not wiped`() = runTest {
        var opens = 0
        var wipes = 0
        var keyClears = 0

        assertFailsWith<DatabaseUnavailableException> {
            DatabaseManager.initializeWithRecovery(
                createDriver = { opens++; throw DatabaseUnavailableException("key store not ready") },
                deleteOnDiskFiles = { wipes++ },
                getKey = { "passphrase" },
                clearKey = { keyClears++ },
            )
        }

        assertEquals(1, opens)
        assertEquals(0, wipes)
        assertEquals(0, keyClears)
    }
}
