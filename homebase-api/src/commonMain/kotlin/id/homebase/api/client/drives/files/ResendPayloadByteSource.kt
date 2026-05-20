package id.homebase.api.client.drives.files

import kotlin.uuid.Uuid

/**
 * Narrow read-only seam over [DriveFileProvider] for the chat group-heal
 * "resend" path. That path pulls an existing file's ALREADY-encrypted payload
 * **and** thumbnail bytes off our own drive and re-attaches them to an
 * `updateFileByUniqueId` request (as pre-encrypted [PayloadFile] / [ThumbnailFile]
 * entries) so a peer receiving the heal'd file gets the complete image — and so
 * the `AppendOrOverwrite` doesn't degrade our own copy.
 *
 * Re-attaching the payload bytes WITHOUT its thumbnails is the
 * group-image-destroyed-on-heal bug: the overwrite wipes the server-side
 * thumbnails, leaving the avatar loader to 404 on every size (warning triangle
 * over the surviving embedded preview).
 *
 * Exposed as an interface so `ConversationService` can be unit-tested with a fake
 * instead of a real [DriveFileProvider] (which needs an HTTP client + disk cache).
 * Implemented by [DriveFileProvider].
 */
interface ResendPayloadByteSource {
    /** Raw, still-encrypted bytes of a payload; null on 404. */
    suspend fun getPayloadBytesEncrypted(driveId: Uuid, fileId: Uuid, key: String): ByteArray?

    /** Raw, still-encrypted bytes of one of a payload's thumbnails; null on 404. */
    suspend fun getThumbBytesEncrypted(
        driveId: Uuid,
        fileId: Uuid,
        payloadKey: String,
        width: Int,
        height: Int,
        lastModified: Long? = null,
    ): ByteArray?
}
