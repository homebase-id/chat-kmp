package id.homebase.api.client.diagnostics

import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.createInMemoryDatabase
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerIpStoreTest {

    // Explicit StandardTestDispatcher for both DB lanes — the bare default-dispatcher
    // DatabaseManager throws on the wasmJs sql.js driver (commonTest runs there too).
    private fun runStoreTest(body: suspend TestScope.(ServerIpStore) -> Unit) = runTest {
        val dbDispatcher = StandardTestDispatcher(testScheduler)
        val db = DatabaseManager(
            { createInMemoryDatabase() },
            dispatcher = dbDispatcher,
            readDispatcher = dbDispatcher,
        )
        try {
            body(ServerIpStore(db))
        } finally {
            db.close()
        }
    }

    @Test
    fun storesAndReadsBackForSameHostname() = runStoreTest { store ->
        store.setLastKnownIp("frodo.example", "203.0.113.7", atMs = 1_700_000_000_000L)
        assertEquals(LastKnownServerIp("203.0.113.7", 1_700_000_000_000L), store.getLastKnownIp("frodo.example"))
    }

    @Test
    fun ignoresIpStoredForADifferentHostname() = runStoreTest { store ->
        store.setLastKnownIp("frodo.example", "203.0.113.7", atMs = 1L)
        assertNull(store.getLastKnownIp("samwise.example"))
    }

    @Test
    fun returnsNullWhenNothingStored() = runStoreTest { store ->
        assertNull(store.getLastKnownIp("frodo.example"))
    }

    @Test
    fun latestWriteWins() = runStoreTest { store ->
        store.setLastKnownIp("frodo.example", "203.0.113.7", atMs = 1L)
        store.setLastKnownIp("frodo.example", "198.51.100.9", atMs = 2L)
        assertEquals(LastKnownServerIp("198.51.100.9", 2L), store.getLastKnownIp("frodo.example"))
    }
}
