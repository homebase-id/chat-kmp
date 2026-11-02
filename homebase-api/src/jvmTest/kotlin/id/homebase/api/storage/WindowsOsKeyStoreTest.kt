package id.homebase.api.storage

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WindowsOsKeyStoreTest {
    private val dir: File = tempStorageDir()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun store(unprotect: (ByteArray) -> ByteArray = { it.reversedArray() }) =
        WindowsOsKeyStore(dir, protect = { it.reversedArray() }, unprotect = unprotect)

    @Test
    fun `no blob file is absent`() {
        assertEquals(OsKeyRead.Absent, store().read())
    }

    @Test
    fun `a written value round trips, is not stored in plain text, and is deleted`() {
        val store = store()
        store.write("secret-key")

        assertFalse(File(dir, "secure_storage.key.dpapi").readText().contains("secret-key"))
        assertEquals(OsKeyRead.Found("secret-key"), store.read())
        store.delete()
        assertEquals(OsKeyRead.Absent, store.read())
    }

    @Test
    fun `a blob from another user or machine reads as absent`() {
        store().write("k")
        listOf(13, 0x8007000D.toInt(), 0x80090005.toInt(), 0x8009000B.toInt()).forEach { code ->
            assertEquals(OsKeyRead.Absent, store { throw DpapiException(code) }.read(), "code $code")
        }
    }

    @Test
    fun `any other DPAPI failure is unreachable`() {
        store().write("k")

        assertIs<OsKeyRead.Unreachable>(store { throw DpapiException(5) }.read())
        assertIs<OsKeyRead.Unreachable>(store { throw IllegalStateException("x") }.read())
        assertTrue(dpapiCodeIsForeignBlob(13))
    }
}
