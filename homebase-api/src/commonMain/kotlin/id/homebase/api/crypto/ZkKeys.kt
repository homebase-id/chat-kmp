package id.homebase.api.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH
import kotlin.coroutines.cancellation.CancellationException

/** A locked or sealed value that doesn't open with the key given, or isn't one. Thrown alike on every platform. */
class ZkOpenException(message: String, cause: Throwable? = null) : Exception(message, cause)

// WebCrypto rejects a bad tag or key as a kotlin.Throwable, not an Exception, so callers catching Exception miss it.
private inline fun <T> opening(open: () -> T): T =
    try {
        open()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ZkOpenException) {
        throw e
    } catch (e: Throwable) {
        throw ZkOpenException("Doesn't open with this key", e)
    }

/** The owner console's `zkLock` (odin-core docs/owner-keys.md): a 12-byte IV, then the AES-GCM ciphertext and its tag. */
object ZkLock {
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH = 16

    suspend fun lock(data: ByteArray, key: ByteArray): ByteArray {
        val iv = ByteArrayUtil.getRndByteArray(IV_LENGTH)
        return iv + AesGcm.encrypt(data, key, iv)
    }

    suspend fun open(locked: ByteArray, key: ByteArray): ByteArray {
        if (locked.size <= IV_LENGTH + TAG_LENGTH) throw ZkOpenException("Not a zkLock value")
        return opening { AesGcm.decrypt(locked.copyOfRange(IV_LENGTH, locked.size), key, locked.copyOf(IV_LENGTH)) }
    }
}

/**
 * `seal` version 1 (odin-core `ZkSeal`): `0x01 ‖ key id ‖ throwaway P-384 point ‖ zkLock`, locked with
 * hkdf-sha256(ECDH, no salt, "homebase-zk-seal"). The key id is the IEEE CRC-32 of the recipient's point.
 */
object ZkSeal {
    private const val VERSION: Byte = 1
    private const val POINT_OFFSET = 5
    private const val POINT_LENGTH = 97
    private val info = "homebase-zk-seal".encodeToByteArray()
    private val ecdh = CryptographyProvider.Default.get(ECDH)

    fun keyId(recipientPoint: ByteArray): UInt = Crc32c.calculateCrc32c(0u, recipientPoint)

    suspend fun seal(data: ByteArray, recipientPoint: ByteArray): ByteArray {
        val recipient = ecdh.publicKeyDecoder(EC.Curve.P384).decodeFromByteArray(EC.PublicKey.Format.RAW.Uncompressed, recipientPoint)
        val throwaway = ecdh.keyPairGenerator(EC.Curve.P384).generateKey()
        val secret = throwaway.privateKey.sharedSecretGenerator().generateSharedSecretToByteArray(recipient)
        return byteArrayOf(VERSION) + ByteArrayUtil.uInt32ToBytes(keyId(recipientPoint)) +
            throwaway.publicKey.encodeToByteArray(EC.PublicKey.Format.RAW.Uncompressed) + ZkLock.lock(data, key(secret))
    }

    /** Opens [sealed] with the recipient's private key, PKCS8 DER. */
    suspend fun open(sealed: ByteArray, privateKeyPkcs8: ByteArray): ByteArray {
        if (sealed.size <= POINT_OFFSET + POINT_LENGTH || sealed[0] != VERSION || sealed[POINT_OFFSET] != 0x04.toByte()) {
            throw ZkOpenException("Not a version 1 sealed value")
        }
        val secret = opening {
            val me = ecdh.privateKeyDecoder(EC.Curve.P384).decodeFromByteArray(EC.PrivateKey.Format.DER.Generic, privateKeyPkcs8)
            val throwaway = ecdh.publicKeyDecoder(EC.Curve.P384)
                .decodeFromByteArray(EC.PublicKey.Format.RAW.Uncompressed, sealed.copyOfRange(POINT_OFFSET, POINT_OFFSET + POINT_LENGTH))
            me.sharedSecretGenerator().generateSharedSecretToByteArray(throwaway)
        }
        return ZkLock.open(sealed.copyOfRange(POINT_OFFSET + POINT_LENGTH, sealed.size), key(secret))
    }

    private suspend fun key(secret: ByteArray): ByteArray = HashUtil.hkdf(secret, null, 16, info)
}
