package id.homebase.api.crypto

import id.homebase.api.common.OdinId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/**
 * Selective Share's key blobs, format 2 (odin-core docs/selective-share-plan.md, "Blob formats"): AES-128-GCM, a
 * 12-byte nonce and a 16-byte tag, each bound by its AAD to where it belongs.
 * - a share row's locked file key: the file's key under its circle's key, for that drive, file and circle;
 * - a circle's key locked with the app's key ([ZkKeyStore][id.homebase.api.youauth.ZkKeyStore]'s AppZkK);
 * - a member's copy of a circle's key, sealed to their drive's write-only key as [ZkSeal] seals.
 */
object ShareKeys {
    const val KEY_LENGTH = 16
    private const val FORMAT: Byte = 2
    private const val NONCE_LENGTH = 12
    private const val TAG_LENGTH = 16
    private const val LOCKED_LENGTH = 1 + NONCE_LENGTH + KEY_LENGTH + TAG_LENGTH

    fun newCircleKey(): ByteArray = ByteArrayUtil.getRndByteArray(KEY_LENGTH)

    suspend fun lockFileKey(circleKey: ByteArray, fileKey: ByteArray, driveId: Uuid, fileId: Uuid, circleId: Uuid): ByteArray =
        lock(circleKey, fileKey, fileKeyAad(driveId, fileId, circleId))

    suspend fun openFileKey(circleKey: ByteArray, locked: ByteArray, driveId: Uuid, fileId: Uuid, circleId: Uuid): ByteArray =
        open(circleKey, locked, fileKeyAad(driveId, fileId, circleId))

    suspend fun lockCircleKey(appKey: ByteArray, circleKey: ByteArray, circleId: Uuid): ByteArray =
        lock(appKey, circleKey, circleId.toDotNetBytes())

    suspend fun openCircleKey(appKey: ByteArray, locked: ByteArray, circleId: Uuid): ByteArray =
        open(appKey, locked, circleId.toDotNetBytes())

    suspend fun sealCircleKey(circleKey: ByteArray, memberPoint: ByteArray, circleId: Uuid, member: OdinId): ByteArray {
        val (head, key) = ZkSeal.agree(FORMAT, memberPoint)
        return head + lock(key, circleKey, sealedAad(circleId, member)).copyOfRange(1, LOCKED_LENGTH)
    }

    suspend fun unsealCircleKey(sealed: ByteArray, privateKeyPkcs8: ByteArray, circleId: Uuid, member: OdinId): ByteArray {
        val key = ZkSeal.agreed(FORMAT, sealed, privateKeyPkcs8)
        val locked = byteArrayOf(FORMAT) + sealed.copyOfRange(ZkSeal.POINT_OFFSET + ZkSeal.POINT_LENGTH, sealed.size)
        return open(key, locked, sealedAad(circleId, member))
    }

    /** `0x04 ‖ x ‖ y` of a P-384 public key given as a JWK. */
    fun pointOfJwk(jwk: String): ByteArray {
        val fields = Json.parseToJsonElement(jwk).jsonObject
        fun coordinate(name: String) = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
            .decode(fields.getValue(name).jsonPrimitive.content)
        return byteArrayOf(0x04) + coordinate("x") + coordinate("y")
    }

    internal fun fileKeyAad(driveId: Uuid, fileId: Uuid, circleId: Uuid) =
        driveId.toDotNetBytes() + fileId.toDotNetBytes() + circleId.toDotNetBytes()

    internal fun sealedAad(circleId: Uuid, member: OdinId) =
        circleId.toDotNetBytes() + member.domainName.lowercase().encodeToByteArray()

    internal suspend fun lock(key: ByteArray, plaintext: ByteArray, aad: ByteArray, nonce: ByteArray = ByteArrayUtil.getRndByteArray(NONCE_LENGTH)) =
        byteArrayOf(FORMAT) + nonce + AesGcm.encrypt(plaintext, key, nonce, aad)

    private suspend fun open(key: ByteArray, locked: ByteArray, aad: ByteArray): ByteArray {
        if (locked.size != LOCKED_LENGTH || locked[0] != FORMAT) throw ZkOpenException("Not a format 2 locked key")
        return opening { AesGcm.decrypt(locked.copyOfRange(1 + NONCE_LENGTH, locked.size), key, locked.copyOfRange(1, 1 + NONCE_LENGTH), aad) }
    }
}

/** A Guid's bytes as .NET's `Guid.ToByteArray()` lays them out: the first three fields little-endian. */
internal fun Uuid.toDotNetBytes(): ByteArray {
    val b = toByteArray()
    return byteArrayOf(b[3], b[2], b[1], b[0], b[5], b[4], b[7], b[6]) + b.copyOfRange(8, 16)
}
