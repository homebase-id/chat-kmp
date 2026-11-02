package id.homebase.api.storage

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OsKeyStoreRealTest {
    @Test
    fun `the real OS store writes, reads, replaces and deletes an entry`() {
        if (System.getenv("HOMEBASE_REAL_KEYSTORE") != "1") return
        val store = selectNativeOsKeyStore(service = "HomebaseKeystoreTest", account = "test-${System.nanoTime()}")
        println("real key store: ${store::class.simpleName}")
        try {
            assertEquals(OsKeyRead.Absent, store.read())
            val first = encodeKeyEnvelope(ByteArray(32) { 1 })
            store.write(first)
            assertEquals(OsKeyRead.Found(first), store.read())
            val second = encodeKeyEnvelope(ByteArray(32) { 2 })
            store.write(second)
            assertEquals(OsKeyRead.Found(second), store.read())
        } finally {
            store.delete()
        }
        assertEquals(OsKeyRead.Absent, store.read())
        assertIs<OsKeyRead.Absent>(store.read())
    }

    @Test
    fun `an existing install migrates its file key into the real OS store and keeps every value`() {
        if (System.getenv("HOMEBASE_REAL_KEYSTORE") != "1") return
        val dir = tempStorageDir()
        val store = selectNativeOsKeyStore(service = "HomebaseKeystoreTest", account = "migrate-${System.nanoTime()}")
        try {
            val fileKey = writeLegacyInstall(dir, mapOf("a" to "1", "b" to "2"))
            val storage = KeyStoreFileStorage(dir, store)
            assertEquals(SecureRead.Found("1"), storage.read("a"))
            assertEquals(SecureRead.Found("2"), storage.read("b"))
            assertFalse(keyStoreFile(dir).exists())
            val found = assertNotNull(store.read() as? OsKeyRead.Found)
            assertContentEquals(fileKey.encoded, decodeKeyEnvelope(found.value))
            assertEquals(SecureRead.Found("1"), KeyStoreFileStorage(dir, store).read("a"))
        } finally {
            store.delete()
            dir.deleteRecursively()
        }
    }
}
