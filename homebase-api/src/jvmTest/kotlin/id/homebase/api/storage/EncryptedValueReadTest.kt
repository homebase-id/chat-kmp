package id.homebase.api.storage

import java.io.IOException
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EncryptedValueReadTest {

    @Test
    fun `a decrypted value is found`() {
        assertEquals(SecureRead.Found("plain"), readEncrypted({ "sealed" }) { "plain" })
    }

    @Test
    fun `no stored value is missing`() {
        assertEquals(SecureRead.Missing, readEncrypted({ null }) { error("must not decrypt") })
    }

    @Test
    fun `a storage read failure is unavailable, not missing`() {
        assertIs<SecureRead.Unavailable>(readEncrypted({ throw IOException("locked") }) { it })
    }

    @Test
    fun `a key store failure while decrypting is unavailable, not missing`() {
        assertIs<SecureRead.Unavailable>(readEncrypted({ "sealed" }) { throw ProviderException("Keystore busy") })
    }

    @Test
    fun `a value sealed by a key the store no longer has is missing`() {
        assertEquals(SecureRead.Missing, readEncrypted({ "sealed" }) { throw AEADBadTagException() })
    }

    @Test
    fun `a malformed value is missing`() {
        assertEquals(SecureRead.Missing, readEncrypted({ "not base64" }) { throw IllegalArgumentException() })
    }
}
