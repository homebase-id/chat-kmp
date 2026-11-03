package id.homebase.api.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KeyMigrationTest {
    private val dir: File = tempStorageDir()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun open(store: FakeOsKeyStore) = KeyStoreFileStorage(dir, store)

    private fun storedKeyBytes(store: FakeOsKeyStore) = decodeKeyEnvelope(assertNotNull(store.stored))

    @Test
    fun `an existing install moves its key to the OS store and keeps every value`() {
        val fileKey = writeLegacyInstall(dir, mapOf("a" to "1", "b" to "2"))
        val store = FakeOsKeyStore()

        val storage = open(store)
        assertEquals(SecureRead.Found("1"), storage.read("a"))
        assertEquals(SecureRead.Found("2"), storage.read("b"))

        assertContentEquals(fileKey.encoded, storedKeyBytes(store))
        assertFalse(keyStoreFile(dir).exists())
    }

    @Test
    fun `a store that holds the key is used and the key file is gone`() {
        val store = FakeOsKeyStore()
        open(store).put("a", "1")

        assertEquals(SecureRead.Found("1"), open(store).read("a"))
        assertFalse(keyStoreFile(dir).exists())
    }

    @Test
    fun `the same key in both places deletes the file`() {
        writeLegacyInstall(dir, mapOf("a" to "1"))
        val store = FakeOsKeyStore(stored = encodeKeyEnvelope(readFileKey(dir).encoded))

        assertEquals(SecureRead.Found("1"), open(store).read("a"))
        assertFalse(keyStoreFile(dir).exists())
        assertEquals(0, store.writeCalls)
    }

    @Test
    fun `a write that throws keeps the file and the values, and the next start migrates`() {
        writeLegacyInstall(dir, mapOf("a" to "1"))
        val store = FakeOsKeyStore().apply { failWrite = IllegalStateException("denied") }

        assertEquals(SecureRead.Found("1"), open(store).read("a"))
        assertTrue(keyStoreFile(dir).exists())

        store.failWrite = null
        assertEquals(SecureRead.Found("1"), open(store).read("a"))
        assertFalse(keyStoreFile(dir).exists())
        assertNotNull(store.stored)
    }

    @Test
    fun `a read back that differs keeps the file and the values`() {
        writeLegacyInstall(dir, mapOf("a" to "1"))
        val store = FakeOsKeyStore()
        val flaky = object : OsKeyStore by store {
            var reads = 0
            override fun read(): OsKeyRead =
                if (reads++ == 0) OsKeyRead.Absent else OsKeyRead.Found(encodeKeyEnvelope(ByteArray(32) { 7 }))
        }

        assertEquals(SecureRead.Found("1"), KeyStoreFileStorage(dir, flaky).read("a"))
        assertTrue(keyStoreFile(dir).exists())
    }

    @Test
    fun `a read back that fails keeps the file and the values`() {
        writeLegacyInstall(dir, mapOf("a" to "1"))
        val store = FakeOsKeyStore()
        val flaky = object : OsKeyStore by store {
            var reads = 0
            override fun read(): OsKeyRead =
                if (reads++ == 0) OsKeyRead.Absent else throw IllegalStateException("gone")
        }

        assertEquals(SecureRead.Found("1"), KeyStoreFileStorage(dir, flaky).read("a"))
        assertTrue(keyStoreFile(dir).exists())
    }

    @Test
    fun `different keys in the store and the file use the one that opens the values and fix the store`() {
        val fileKey = writeLegacyInstall(dir, mapOf("a" to "1"))
        val store = FakeOsKeyStore(stored = encodeKeyEnvelope(ByteArray(32) { 9 }))

        assertEquals(SecureRead.Found("1"), open(store).read("a"))

        assertContentEquals(fileKey.encoded, storedKeyBytes(store))
        assertFalse(keyStoreFile(dir).exists())
    }

    @Test
    fun `different keys where the store key opens the values keep the store key`() {
        val store = FakeOsKeyStore()
        open(store).put("a", "1")
        val storeKey = storedKeyBytes(store)
        KeyStoreFileStorage(File(dir, "other").apply { mkdirs() }, FakeOsKeyStore(noStore())).put("x", "y")
        File(dir, "other/secure_storage.p12").copyTo(keyStoreFile(dir))

        assertEquals(SecureRead.Found("1"), open(store).read("a"))

        assertContentEquals(storeKey, storedKeyBytes(store))
        assertFalse(keyStoreFile(dir).exists())
    }

    @Test
    fun `with nothing to decrypt the file key wins`() {
        val fileKey = writeLegacyInstall(dir, mapOf("a" to "1"))
        dataFile(dir).delete()
        val store = FakeOsKeyStore(stored = encodeKeyEnvelope(ByteArray(32) { 9 }))

        open(store).put("b", "2")

        assertContentEquals(fileKey.encoded, storedKeyBytes(store))
    }

    @Test
    fun `an unreachable store with a key file uses the file key and keeps it`() {
        writeLegacyInstall(dir, mapOf("a" to "1"))
        val store = FakeOsKeyStore(OsKeyRead.Unreachable(IllegalStateException("locked")))

        assertEquals(SecureRead.Found("1"), open(store).read("a"))
        assertTrue(keyStoreFile(dir).exists())
        assertEquals(0, store.writeCalls)
    }

    @Test
    fun `an absent store with sealed values and no file key makes no new key until the user resets`() {
        val store = FakeOsKeyStore()
        open(store).put("a", "1")
        store.stored = null
        val writes = store.writeCalls

        val storage = open(store)
        assertIs<SecureRead.Unavailable>(storage.read("a"))
        assertFailsWith<OsKeyStoreUnavailableException> { storage.put("a", "2") }
        assertEquals(writes, store.writeCalls)

        storage.clear()
        storage.put("a", "2")
        assertEquals(SecureRead.Found("2"), storage.read("a"))
        assertEquals(32, storedKeyBytes(store)?.size)
    }

    @Test
    fun `migration tightens an old world readable data file`() {
        writeLegacyInstall(dir, mapOf("a" to "1"))
        if (!dir.toPath().fileSystem.supportedFileAttributeViews().contains("posix")) return
        Files.setPosixFilePermissions(dataFile(dir).toPath(), PosixFilePermissions.fromString("rw-r--r--"))

        assertEquals(SecureRead.Found("1"), open(FakeOsKeyStore()).read("a"))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dataFile(dir).toPath())))
    }
}
