@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.contacts

import id.homebase.api.serialization.UuidSerializer
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Body for PUT /api/v2/contacts/{uniqueId}/image.
 *
 * The image is **client-encrypted**: [iv], [content], and each thumbnail's content are base64 of
 * AES-CBC ciphertext produced under the contact file's AES key, all sharing the single [iv]. The
 * server stores the ciphertext verbatim under payload key `prfl_pic` and never sees the plaintext.
 *
 * Binary fields are base64 [String]s on purpose: kotlinx encodes a `ByteArray` as a JSON number
 * array, which the server (expecting a base64 string) will not bind.
 */
@Serializable
data class SetContactImageRequest(
    @Serializable(with = UuidSerializer::class) val versionTag: Uuid,
    val contentType: String,
    /** base64 of the 16-byte IV (shared by the image and all its thumbnails). */
    val iv: String,
    /** base64 of the ENCRYPTED image bytes. */
    val content: String,
    val thumbnails: List<ContactImageThumbnail> = emptyList(),
)

@Serializable
data class ContactImageThumbnail(
    val pixelWidth: Int,
    val pixelHeight: Int,
    val contentType: String,
    /** base64 of the ENCRYPTED thumbnail bytes (same IV as the image). */
    val content: String,
)
