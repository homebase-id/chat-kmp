package id.homebase.api.storage

import kotlin.test.Test
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
}
