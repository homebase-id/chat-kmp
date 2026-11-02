package id.homebase.api.storage

import id.homebase.api.sync.database.DatabaseKeyManager
import id.homebase.api.sync.database.DatabaseUnavailableException
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class KeyUnavailableTest {
    private val dir: File = tempStorageDir()
    private var now = 1_000L

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun sealedByOtherSession(): FakeOsKeyStore {
        val store = FakeOsKeyStore()
        KeyStoreFileStorage(dir, store).apply { put("k", "v"); put("odin_db_encryption_key", "pass") }
        return store
    }

    private fun locked(store: FakeOsKeyStore) {
        store.forced = OsKeyRead.Unreachable(IllegalStateException("locked"))
    }

    @Test
    fun `an unreachable store reads as unavailable, never writes and leaves the files alone`() {
        val store = sealedByOtherSession()
        val before = dataFile(dir).readBytes()
        val writes = store.writeCalls
        locked(store)
        val storage = KeyStoreFileStorage(dir, store) { now }

        assertIs<SecureRead.Unavailable>(storage.read("k"))
        assertIs<SecureRead.Unavailable>(storage.read("odin_db_encryption_key"))
        assertFailsWith<OsKeyStoreUnavailableException> { storage.put("k", "other") }

        assertEquals(writes, store.writeCalls)
        assertContentEquals(before, dataFile(dir).readBytes())
    }

    @Test
    fun `the database key manager never regenerates a key while the store is unreachable`() {
        val store = sealedByOtherSession()
        locked(store)
        val storage = KeyStoreFileStorage(dir, store) { now }
        var puts = 0

        assertFailsWith<DatabaseUnavailableException> {
            DatabaseKeyManager.getOrGenerateKey(storage.read("odin_db_encryption_key")) { puts++; storage.put("odin_db_encryption_key", it) }
        }

        assertEquals(0, puts)
    }

    @Test
    fun `a garbled key is retried and then unavailable, not missing`() {
        val store = sealedByOtherSession()
        store.garbledReads = 100
        val storage = KeyStoreFileStorage(dir, store) { now }

        assertIs<SecureRead.Unavailable>(storage.read("k"))
    }

    @Test
    fun `a garbled read that clears up on retry opens the value`() {
        val store = sealedByOtherSession()
        store.garbledReads = 1

        assertEquals(SecureRead.Found("v"), KeyStoreFileStorage(dir, store) { now }.read("k"))
    }

    @Test
    fun `the cooldown makes one store call across many reads and retries afterwards`() {
        val store = sealedByOtherSession()
        locked(store)
        val calls = store.readCalls
        val storage = KeyStoreFileStorage(dir, store) { now }

        repeat(20) { assertIs<SecureRead.Unavailable>(storage.read("k")) }
        val duringCooldown = store.readCalls - calls
        assertEquals(3, duringCooldown)

        now += 31_000
        store.forced = null
        assertEquals(SecureRead.Found("v"), storage.read("k"))
    }

    @Test
    fun `reading a property that is not there never touches the store`() {
        val store = sealedByOtherSession()
        val calls = store.readCalls
        val storage = KeyStoreFileStorage(dir, store) { now }

        assertEquals(SecureRead.Missing, storage.read("nope"))
        assertEquals(false, storage.contains("nope"))
        storage.remove("nope")

        assertEquals(calls, store.readCalls)
    }

    @Test
    fun `a linkage error from the store reads as a fallback, not a crash`() {
        val store = sealedByOtherSession()
        val broken = object : OsKeyStore by store {
            override fun read(): OsKeyRead = throw UnsatisfiedLinkError("libsecret")
        }

        assertIs<SecureRead.Unavailable>(KeyStoreFileStorage(dir, broken).read("k"))
    }
}
