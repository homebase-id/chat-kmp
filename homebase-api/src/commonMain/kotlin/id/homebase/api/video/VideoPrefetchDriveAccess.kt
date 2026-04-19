package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.BytesResponse
import kotlin.uuid.Uuid

/** Narrow view of [id.homebase.api.client.drives.files.DriveFileProvider] that [VideoPreloader]
 *  and [resolveVideoMetadata] need. Kept separate so tests can fake the drive layer without
 *  constructing the real Ktor / credentials stack. */
interface VideoPrefetchDriveAccess {
    suspend fun prefetchPayload(
        driveId: Uuid,
        fileId: Uuid,
        key: String,
        onDownloadProgress: ((Float) -> Unit)? = null,
    )

    suspend fun prefetchPayloadChunk(
        driveId: Uuid,
        fileId: Uuid,
        key: String,
        chunkStart: Long,
        chunkLength: Long,
        onDownloadProgress: ((Float) -> Unit)? = null,
    )

    suspend fun getPayloadBytesDecrypted(
        driveId: Uuid,
        fileId: Uuid,
        key: String,
        keyHeader: KeyHeader,
        chunkStart: Long? = null,
        chunkLength: Long? = null,
        onDownloadProgress: ((Float) -> Unit)? = null,
    ): BytesResponse?
}
