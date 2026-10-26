package id.homebase.api.notifications

import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.AesGcm
import id.homebase.api.crypto.HashUtil
import id.homebase.api.serialization.OdinSystemSerializer
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/** A file's header as odin-core's PushPreviewSealer seals it into a push. */
@Serializable
internal data class PushPreview(
    val uniqueId: String? = null,
    val groupId: String? = null,
    val fileType: Int = 0,
    val dataType: Int = 0,
    val content: String? = null,
    val contentTruncated: Boolean = false,
)

private const val PUSH_PREVIEW_NONCE_BYTES = 12
private val PUSH_PREVIEW_KEY_SALT = "homebase-push-preview-v1".encodeToByteArray()

/**
 * The key the identity seals push previews to, registered with the push subscription. Derived
 * from the shared secret so the extension needs nothing it doesn't already hold, and one-way so
 * the identity storing it learns nothing about the secret.
 */
suspend fun pushPreviewKey(sharedSecret: SecureByteArray): ByteArray =
    HashUtil.hkdf(sharedSecret.unsafeBytes, PUSH_PREVIEW_KEY_SALT, 16)

/** base64(nonce[12] || AES-128-GCM ciphertext || tag[16]) of the preview's JSON. */
internal suspend fun openPushPreview(sealed: String, key: ByteArray): PushPreview {
    val bytes = Base64.decode(sealed)
    val plaintext = AesGcm.decrypt(
        bytes.copyOfRange(PUSH_PREVIEW_NONCE_BYTES, bytes.size),
        key,
        bytes.copyOfRange(0, PUSH_PREVIEW_NONCE_BYTES),
    )
    return OdinSystemSerializer.json.decodeFromString(PushPreview.serializer(), plaintext.decodeToString())
}
