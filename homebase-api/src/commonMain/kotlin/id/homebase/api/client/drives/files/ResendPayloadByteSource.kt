package id.homebase.api.client.drives.files

import kotlin.uuid.Uuid

/**
 * A file's payloads and thumbnails as stored, still encrypted, for a resend or heal to re-attach: an
 * AppendOrOverwrite without the thumbnails wipes them server-side. [id.homebase.api.client.payload.PayloadReader.resendSource]
 * is the app's; tests pass a fake.
 */
interface ResendPayloadByteSource {
    /** Raw, still-encrypted bytes of a payload; null on 404. */
    suspend fun storedPayloadBytes(driveId: Uuid, fileId: Uuid, key: String): ByteArray?

    /** Raw, still-encrypted bytes of one of a payload's thumbnails; null on 404. */
    suspend fun storedThumbBytes(
        driveId: Uuid,
        fileId: Uuid,
        payloadKey: String,
        width: Int,
        height: Int,
        lastModified: Long? = null,
    ): ByteArray?
}
