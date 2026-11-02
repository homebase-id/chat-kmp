package id.homebase.api.storage

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LogoutStorageTest {
    private val dir: File = tempStorageDir()
    private val store = FakeOsKeyStore()
    private val storage = KeyStoreFileStorage(dir, store)

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `after logout removals the login and zk values are gone from the file and the database key still reads`() {
        storage.put("credential", "token")
        storage.put("zk", "secret")
        storage.put("odin_db_encryption_key", "pass")
        val sealed = dataFile(dir).readText()

        storage.remove("credential")
        storage.remove("zk")

        val bytes = dataFile(dir).readText()
        assertFalse(storage.contains("credential"))
        assertFalse(storage.contains("zk"))
        assertFalse(bytes.contains(sealed.lines().first { it.startsWith("credential=") }.substringAfter("=")))
        assertEquals(SecureRead.Found("pass"), storage.read("odin_db_encryption_key"))
        assertEquals(0, store.deleteCalls)
    }

    @Test
    fun `clear deletes the OS entry`() {
        storage.put("a", "1")
        storage.clear()

        assertEquals(1, store.deleteCalls)
        assertEquals(null, store.stored)
        assertEquals(SecureRead.Missing, storage.read("a"))
    }

    @Test
    fun `emptying the store deletes the OS entry`() {
        storage.put("a", "1")
        storage.remove("a")

        assertEquals(1, store.deleteCalls)
    }
}
