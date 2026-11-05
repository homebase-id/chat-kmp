package id.homebase.api.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FallbackFileTest {
    private val dir: File = tempStorageDir()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private val posix get() = dir.toPath().fileSystem.supportedFileAttributeViews().contains("posix")

    @Test
    fun `no store on an empty folder round trips through a 0600 key file and says so`() {
        val storage = KeyStoreFileStorage(dir, FakeOsKeyStore(noStore()))
        storage.put("k", "v")

        assertEquals(SecureRead.Found("v"), storage.read("k"))
        assertTrue(storage.usingFallbackFile)
        if (posix) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(keyStoreFile(dir).toPath())))
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dataFile(dir).toPath())))
        }
    }

    @Test
    fun `an old world readable key file is tightened`() {
        writeLegacyInstall(dir, mapOf("k" to "v"))
        if (posix) Files.setPosixFilePermissions(keyStoreFile(dir).toPath(), PosixFilePermissions.fromString("rw-r--r--"))

        assertEquals(SecureRead.Found("v"), KeyStoreFileStorage(dir, FakeOsKeyStore(noStore())).read("k"))
        if (posix) assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(keyStoreFile(dir).toPath())))
    }

    @Test
    fun `a store that appears later takes the fallback key`() {
        writeLegacyInstall(dir, mapOf("k" to "v"))
        val store = FakeOsKeyStore()

        assertEquals(SecureRead.Found("v"), KeyStoreFileStorage(dir, store).read("k"))
        assertFalse(keyStoreFile(dir).exists())
        assertTrue(store.stored != null)
    }

    @Test
    fun `no store with sealed values and no key file is unavailable and makes no second key`() {
        KeyStoreFileStorage(dir, FakeOsKeyStore()).put("k", "v")
        val storage = KeyStoreFileStorage(dir, FakeOsKeyStore(noStore()))

        assertIs<SecureRead.Unavailable>(storage.read("k"))
        assertFailsWithUnavailable { storage.put("k", "w") }
        assertFalse(keyStoreFile(dir).exists())
    }

    private fun assertFailsWithUnavailable(block: () -> Unit) {
        kotlin.test.assertFailsWith<OsKeyStoreUnavailableException> { block() }
    }
}
