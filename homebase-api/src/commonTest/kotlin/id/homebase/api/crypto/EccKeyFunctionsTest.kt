package id.homebase.api.crypto

import id.homebase.homebasekmppoc.prototype.lib.core.SecureByteArray
import kotlinx.coroutines.test.runTest
import kotlin.compareTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EccKeyFunctionsTest {

    // ========================================================================
    // Key Generation Tests
    // ========================================================================

    @Test
    fun testGenerateEccKeyPair_P256() = runTest {
        val password = SecureByteArray("test-password-123".encodeToByteArray())
        val keyPair = generateEccKeyPair(password, EccKeySize.P256, expirationHours = 24)

        // Verify public key
        assertEquals(EccKeySize.P256, keyPair.publicKey.keySize)
        assertTrue(keyPair.publicKey.publicKeyDer.unsafeBytes.isNotEmpty())
        assertTrue(keyPair.publicKey.crc32c compareTo 0u)

        // Verify private key is encrypted
        assertTrue(keyPair.privateKey.encryptedKey.unsafeBytes.isNotEmpty())
        assertTrue(keyPair.privateKey.iv.unsafeBytes.size == 16)
        assertTrue(keyPair.privateKey.keyHash.unsafeBytes.isNotEmpty())

        // Verify timestamps
        assertTrue(keyPair.publicKey.expiration.compareTo(keyPair.privateKey.createdTimeStamp))
    }

    @Test
    fun testGenerateEccKeyPair_P384() = runTest {
        val password = SecureByteArray("test-password-456".encodeToByteArray())
        val keyPair = generateEccKeyPair(password, EccKeySize.P384, expirationHours = 1)

        assertEquals(EccKeySize.P384, keyPair.publicKey.keySize)
        assertTrue(keyPair.publicKey.publicKeyDer.unsafeBytes.isNotEmpty())
    }

    @Test
    fun testGenerateEccKeyPair_DifferentPasswordsProduceDifferentEncryption() = runTest {
        val password1 = SecureByteArray("password1".encodeToByteArray())
        val password2 = SecureByteArray("password2".encodeToByteArray())

        val keyPair1 = generateEccKeyPair(password1, EccKeySize.P256, 1)
        val keyPair2 = generateEccKeyPair(password2, EccKeySize.P256, 1)

        // Different passwords should produce different key hashes
        assertNotEquals(
            keyPair1.privateKey.keyHash.unsafeBytes.contentToString(),
            keyPair2.privateKey.keyHash.unsafeBytes.contentToString()
        )
    }

    // ========================================================================
    // JWK Conversion Tests
    // ========================================================================

    @Test
    fun testPublicKeyToJwk_RoundTrip_P256() = runTest {
        val password = SecureByteArray("test".encodeToByteArray())
        val original = generateEccKeyPair(password, EccKeySize.P256, 1)

        // Convert to JWK and back
        val jwk = publicKeyToJwk(original.publicKey)
        val restored = publicKeyFromJwk(jwk, 1)

        // Public keys should match
        assertEquals(
            original.publicKey.publicKeyDer.unsafeBytes.contentToString(),
            restored.publicKeyDer.unsafeBytes.contentToString()
        )
        assertEquals(original.publicKey.keySize, restored.keySize)
    }

    @Test
    fun testPublicKeyToJwk_RoundTrip_P384() = runTest {
        val password = SecureByteArray("test".encodeToByteArray())
        val original = generateEccKeyPair(password, EccKeySize.P384, 1)

        val jwk = publicKeyToJwk(original.publicKey)
        val restored = publicKeyFromJwk(jwk, 1)

        assertEquals(
            original.publicKey.publicKeyDer.unsafeBytes.contentToString(),
            restored.publicKeyDer.unsafeBytes.contentToString()
        )
        assertEquals(original.publicKey.keySize, restored.keySize)
    }

    @Test
    fun testPublicKeyToJwkBase64Url_RoundTrip() = runTest {
        val password = SecureByteArray("test".encodeToByteArray())
        val original = generateEccKeyPair(password, EccKeySize.P256, 1)

        val jwkBase64 = publicKeyToJwkBase64Url(original.publicKey)
        val restored = publicKeyFromJwkBase64Url(jwkBase64, 1)

        assertEquals(
            original.publicKey.publicKeyDer.unsafeBytes.contentToString(),
            restored.publicKeyDer.unsafeBytes.contentToString()
        )
    }

    @Test
    fun testPublicKeyFromJwk_InvalidKeyType() = runTest {
        val invalidJwk = """{"kty":"RSA","n":"...","e":"..."}"""

        assertFailsWith<IllegalArgumentException> {
            publicKeyFromJwk(invalidJwk, 1)
        }
    }

    @Test
    fun testPublicKeyFromJwk_MissingCurve() = runTest {
        val invalidJwk = """{"kty":"EC","x":"AAAA","y":"BBBB"}"""

        assertFailsWith<IllegalArgumentException> {
            publicKeyFromJwk(invalidJwk, 1)
        }
    }

    // ========================================================================
    // Private Key Decryption Tests
    // ========================================================================

    @Test
    fun testDecryptPrivateKey_CorrectPassword() = runTest {
        val password = SecureByteArray("correct-password".encodeToByteArray())
        val keyPair = generateEccKeyPair(password, EccKeySize.P256, 1)

        // Should decrypt successfully
        val decrypted = decryptPrivateKey(keyPair.privateKey, password)
        assertTrue(decrypted.isNotEmpty())
    }

    @Test
    fun testDecryptPrivateKey_WrongPassword() = runTest {
        val correctPassword = SecureByteArray("correct".encodeToByteArray())
        val wrongPassword = SecureByteArray("wrong".encodeToByteArray())

        val keyPair = generateEccKeyPair(correctPassword, EccKeySize.P256, 1)

        // Should fail with wrong password
        assertFailsWith<IllegalStateException> {
            decryptPrivateKey(keyPair.privateKey, wrongPassword)
        }
    }

    // ========================================================================
    // ECDH Key Agreement Tests
    // ========================================================================

    @Test
    fun testPerformEcdhKeyAgreement_P256_BothSides() = runTest {
        val alicePassword = SecureByteArray("alice-pwd".encodeToByteArray())
        val bobPassword = SecureByteArray("bob-pwd".encodeToByteArray())

        // Generate key pairs for Alice and Bob
        val aliceKeyPair = generateEccKeyPair(alicePassword, EccKeySize.P256, 1)
        val bobKeyPair = generateEccKeyPair(bobPassword, EccKeySize.P256, 1)

        // Use same salt for both
        val salt = ByteArray(16) { it.toByte() }

        // Alice computes shared secret using Bob's public key
        val aliceSharedSecret = performEcdhKeyAgreement(
            aliceKeyPair,
            alicePassword,
            bobKeyPair.publicKey,
            salt
        )

        // Bob computes shared secret using Alice's public key
        val bobSharedSecret = performEcdhKeyAgreement(
            bobKeyPair,
            bobPassword,
            aliceKeyPair.publicKey,
            salt
        )

        // Shared secrets should match
        assertEquals(
            aliceSharedSecret.unsafeBytes.contentToString(),
            bobSharedSecret.unsafeBytes.contentToString()
        )
    }

    @Test
    fun testPerformEcdhKeyAgreement_P384_BothSides() = runTest {
        val alicePassword = SecureByteArray("alice-pwd".encodeToByteArray())
        val bobPassword = SecureByteArray("bob-pwd".encodeToByteArray())

        val aliceKeyPair = generateEccKeyPair(alicePassword, EccKeySize.P384, 1)
        val bobKeyPair = generateEccKeyPair(bobPassword, EccKeySize.P384, 1)

        val salt = ByteArray(16) { it.toByte() }

        val aliceSharedSecret = performEcdhKeyAgreement(
            aliceKeyPair,
            alicePassword,
            bobKeyPair.publicKey,
            salt
        )

        val bobSharedSecret = performEcdhKeyAgreement(
            bobKeyPair,
            bobPassword,
            aliceKeyPair.publicKey,
            salt
        )

        assertEquals(
            aliceSharedSecret.unsafeBytes.contentToString(),
            bobSharedSecret.unsafeBytes.contentToString()
        )
    }

    @Test
    fun testPerformEcdhKeyAgreement_DifferentSaltsProduceDifferentSecrets() = runTest {
        val alicePassword = SecureByteArray("alice".encodeToByteArray())
        val bobPassword = SecureByteArray("bob".encodeToByteArray())

        val aliceKeyPair = generateEccKeyPair(alicePassword, EccKeySize.P256, 1)
        val bobKeyPair = generateEccKeyPair(bobPassword, EccKeySize.P256, 1)

        val salt1 = ByteArray(16) { 0x01 }
        val salt2 = ByteArray(16) { 0x02 }

        val secret1 =
            performEcdhKeyAgreement(aliceKeyPair, alicePassword, bobKeyPair.publicKey, salt1)
        val secret2 =
            performEcdhKeyAgreement(aliceKeyPair, alicePassword, bobKeyPair.publicKey, salt2)

        // Different salts should produce different derived keys
        assertNotEquals(
            secret1.unsafeBytes.contentToString(),
            secret2.unsafeBytes.contentToString()
        )
    }

    @Test
    fun testPerformEcdhKeyAgreement_WrongPassword() = runTest {
        val correctPassword = SecureByteArray("correct".encodeToByteArray())
        val wrongPassword = SecureByteArray("wrong".encodeToByteArray())

        val aliceKeyPair = generateEccKeyPair(correctPassword, EccKeySize.P256, 1)
        val bobKeyPair = generateEccKeyPair(correctPassword, EccKeySize.P256, 1)

        val salt = ByteArray(16) { it.toByte() }

        // Should fail with wrong password
        assertFailsWith<IllegalStateException> {
            performEcdhKeyAgreement(aliceKeyPair, wrongPassword, bobKeyPair.publicKey, salt)
        }
    }

    @Test
    fun testPerformEcdhKeyAgreement_InvalidSaltSize() = runTest {
        val password = SecureByteArray("password".encodeToByteArray())
        val aliceKeyPair = generateEccKeyPair(password, EccKeySize.P256, 1)
        val bobKeyPair = generateEccKeyPair(password, EccKeySize.P256, 1)

        // Salt too small (less than 16 bytes)
        val invalidSalt = ByteArray(8)

        assertFailsWith<IllegalArgumentException> {
            performEcdhKeyAgreement(aliceKeyPair, password, bobKeyPair.publicKey, invalidSalt)
        }
    }

    // ========================================================================
    // Integration Tests
    // ========================================================================

    @Test
    fun testFullFlow_KeyGeneration_JwkExchange_Ecdh() = runTest {
        // Alice and Bob each generate their key pairs
        val alicePassword = SecureByteArray("alice-secret".encodeToByteArray())
        val bobPassword = SecureByteArray("bob-secret".encodeToByteArray())

        val aliceKeyPair = generateEccKeyPair(alicePassword, EccKeySize.P256, 24)
        val bobKeyPair = generateEccKeyPair(bobPassword, EccKeySize.P256, 24)

        // Alice sends her public key as JWK to Bob
        val alicePublicJwk = publicKeyToJwkBase64Url(aliceKeyPair.publicKey)

        // Bob sends his public key as JWK to Alice
        val bobPublicJwk = publicKeyToJwkBase64Url(bobKeyPair.publicKey)

        // Alice receives Bob's public key
        val bobPublicKeyForAlice = publicKeyFromJwkBase64Url(bobPublicJwk, 24)

        // Bob receives Alice's public key
        val alicePublicKeyForBob = publicKeyFromJwkBase64Url(alicePublicJwk, 24)

        // They agree on a salt (e.g., sent by server)
        val agreedSalt = ByteArray(32) { 0x42 }

        // Alice computes shared secret
        val aliceSharedSecret = performEcdhKeyAgreement(
            aliceKeyPair,
            alicePassword,
            bobPublicKeyForAlice,
            agreedSalt
        )

        // Bob computes shared secret
        val bobSharedSecret = performEcdhKeyAgreement(
            bobKeyPair,
            bobPassword,
            alicePublicKeyForBob,
            agreedSalt
        )

        // Both should have the same shared secret
        assertEquals(
            aliceSharedSecret.unsafeBytes.contentToString(),
            bobSharedSecret.unsafeBytes.contentToString()
        )

        // Derived key should be 16 bytes (as specified in performEcdhKeyAgreement)
        assertEquals(16, aliceSharedSecret.unsafeBytes.size)
    }
}
