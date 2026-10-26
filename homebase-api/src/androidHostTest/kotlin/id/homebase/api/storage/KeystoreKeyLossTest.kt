package id.homebase.api.storage

import android.security.keystore.KeyPermanentlyInvalidatedException
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class KeystoreKeyLossTest {

    @Test
    fun `a permanently invalidated key is lost`() {
        assertTrue(KeyPermanentlyInvalidatedException().isKeyLost())
    }

    @Test
    fun `a key load that failed because the key was invalidated is lost`() {
        assertTrue(UnrecoverableKeyException("Failed to obtain information about key").initCause(KeyPermanentlyInvalidatedException()).isKeyLost())
    }

    @Test
    fun `keystore failures that also mean not ready yet are not lost`() {
        assertFalse(UnrecoverableKeyException("Failed to obtain information about key").isKeyLost())
        assertFalse(InvalidKeyException("Keystore operation failed").isKeyLost())
        assertFalse(ProviderException("Keystore not initialized").isKeyLost())
        assertFalse(KeyStoreException("system error").isKeyLost())
    }
}
