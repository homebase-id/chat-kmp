package id.homebase.api.crypto

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.SecureByteArray
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The vectors odin-core (ClientLockedKeyHeaderVectorTests) and odin-js (ZeroKnowledgeKeys.test.ts) check. */
class ZkKeysTest {

    private fun b64(s: String) = Base64.decode(s)

    private fun point(jwk: String): ByteArray {
        fun coordinate(name: String) = Base64UrlEncoder.decode(Regex("\"$name\":\"([^\"]+)\"").find(jwk)!!.groupValues[1])
        return byteArrayOf(0x04) + coordinate("x") + coordinate("y")
    }

    private val sealPrivateKey =
        "MIG2AgEAMBAGByqGSM49AgEGBSuBBAAiBIGeMIGbAgEBBDAdIBNZdX4cc2HkbmyjAsYLgBCJOD4PKl+19MNegdIFHBNvBHXjKKPd1U43jVFC4W2hZANiAAQh7s8LrEkJbWspBmjbFT45ZclVZc9go3WGP86OB1nf16QB+2o3yNTYCg0YJ4Atpknyf0xxJGe5a/LTuILGNwlUaPDmFSyzZ4ALW18WutsGr8ItoW2/V6/fK5tpF8iT70k="
    private val sealPublicKey =
        """{"kty":"EC","crv":"P-384","x":"Ie7PC6xJCW1rKQZo2xU-OWXJVWXPYKN1hj_OjgdZ39ekAftqN8jU2AoNGCeALaZJ","y":"8n9McSRnuWvy07iCxjcJVGjw5hUss2eAC1tfFrrbBq_CLaFtv1ev3yubaRfIk-9J"}"""
    private val sealed =
        "AYGsbtYEJzx9/APLDFRU02ky+GZwV8b3H7z+kwGsq61keI1Spr1TwEe4xemX1gXqt4ebsODaFTM9CTX1ZzYz8AEOKmwRfyCxFiAYyUY9XK31wB7oShI/GiQX6RjUGj2KekD+/f9GXM+hXaDl9UqGteiAsqESPMoIVY4iVeGO3GC/P2Dy4Igq2MsVPmZeNSxogKR0ZeC+rPTMjyeOkbM="

    private val driveKey = "AAECAwQFBgcICQoLDA0ODw=="
    private val iv = "EBESExQVFhcYGRobHB0eHw=="
    private val aesKey = "ICEiIyQlJicoKSorLC0uLw=="

    @Test
    fun theSharedSealVectorOpens() = runTest {
        assertEquals("correct horse battery staple", ZkSeal.open(b64(sealed), b64(sealPrivateKey)).decodeToString())
        assertEquals(0x81ac6ed6u, ZkSeal.keyId(point(sealPublicKey)))
    }

    @Test
    fun aSealedKeyHeaderOpensWithTheWriteOnlyPrivateKey() = runTest {
        val writeOnlyPrivateKey =
            "MIG2AgEAMBAGByqGSM49AgEGBSuBBAAiBIGeMIGbAgEBBDCZAel29rv0BgBwa7LGp4+tmSi5g4LlRzkW9/ZLQNgeVdrB44imUOGeaKmn1eiPZsShZANiAATKPHfd0ojszgjchR06XVN2p/iaosbJedgQGD86jcH39CM7EjWiXa6wVTW3cWGdDXwWQYx4NHDg8lIWULARJhvn02W1/eoTtDkhdpQrY6KmVbxpY/ehNZlDlhlh3MntF40="
        val sealedKeyHeader =
            "ARRSmXQEgEhVDqJf8V4wR/vfRnjuBiMDCgpqQ0EvwP8Sbhg/htjK6DPN0tPu9kf4VKIvY0JpI7ekZehCr+RJr6buacGObBkW+4KXwPFn4aNzze/w+3mTcGuj+8oKsvICcbFc5rPogLwn6Oz/OQuWmSn3xtY70ljeN7b6eV3wkBqmS0sKbr1o/iBvA+hgSlg/4KiDGsTq2pxlrCRLPvr2Vxjk"
        assertContentEquals(b64(iv) + b64(aesKey), ZkSeal.open(b64(sealedKeyHeader), b64(writeOnlyPrivateKey)))
    }

    @Test
    fun aSealOpensOnlyWithItsPrivateKey() = runTest {
        val (privateKey, publicPoint) = keyPair()
        val sealedHere = ZkSeal.seal(byteArrayOf(1, 2, 3), publicPoint)
        assertContentEquals(byteArrayOf(1, 2, 3), ZkSeal.open(sealedHere, privateKey))
        assertFailsWith<ZkOpenException> { ZkSeal.open(sealedHere, b64(sealPrivateKey)) }
        assertEquals(ZkSeal.keyId(publicPoint), ByteArrayUtil.bytesToInt32(sealedHere.copyOfRange(1, 5)).toUInt())
    }

    @Test
    fun theSharedLockVectorOpens() = runTest {
        val secondPasswordKey = b64("+pL3zwkIcDcDpYKkLbVc4A==")
        val locked = b64("ICEiIyQlJicoKSorjWGICGzIuJPS3SPnEBWvlaM3EZ0jd9msGp8vJrhDYXA=")
        assertContentEquals(b64("EBESExQVFhcYGRobHB0eHw=="), ZkLock.open(locked, secondPasswordKey))
        assertFailsWith<ZkOpenException> { ZkLock.open(locked, b64(driveKey)) }
    }

    @Test
    fun aZkDriveKeyHeaderIsTheFileKeyLockedWithTheDriveKey() = runTest {
        val fileKey = KeyHeader(iv = b64(iv), aesKey = SecureByteArray(b64(aesKey)))
        val locked = EncryptedKeyHeader.encryptKeyHeaderAes(fileKey, b64(iv), SecureByteArray(b64(driveKey)))
        assertContentEquals(b64("xqE7N4ePW4JvT4FiocjYeQeFPxlO+hclYcV072RPhmMJbAd9FxFTFUuXv4ql/85L"), locked.encryptedAesKey)
    }

    private suspend fun keyPair(): Pair<ByteArray, ByteArray> {
        val password = SecureByteArray(ByteArrayUtil.getRndByteArray(16))
        val pair = generateEccKeyPair(password, EccKeySize.P384, 1)
        return decryptPrivateKey(pair.privateKey, password) to point(publicKeyToJwk(pair.publicKey))
    }
}
