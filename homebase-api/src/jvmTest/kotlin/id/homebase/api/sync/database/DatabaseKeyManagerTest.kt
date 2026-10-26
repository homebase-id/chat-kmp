package id.homebase.api.sync.database

import id.homebase.api.storage.SecureRead
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatabaseKeyManagerTest {

    @Test
    fun `an unreadable passphrase is never replaced`() {
        val writes = mutableListOf<String>()

        assertFailsWith<DatabaseUnavailableException> {
            DatabaseKeyManager.getOrGenerateKey(SecureRead.Unavailable(IOException("key store not ready")), writes::add)
        }

        assertTrue(writes.isEmpty())
    }

    @Test
    fun `a missing passphrase is generated and stored`() {
        val writes = mutableListOf<String>()

        val key = DatabaseKeyManager.getOrGenerateKey(SecureRead.Missing, writes::add)

        assertEquals(listOf(key), writes)
    }

    @Test
    fun `a stored passphrase is returned as is`() {
        val writes = mutableListOf<String>()

        assertEquals("stored", DatabaseKeyManager.getOrGenerateKey(SecureRead.Found("stored"), writes::add))
        assertTrue(writes.isEmpty())
    }
}
