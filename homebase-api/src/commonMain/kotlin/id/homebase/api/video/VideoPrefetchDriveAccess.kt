package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.BytesResponse
import kotlin.uuid.Uuid

/** One video's payload reads, from [id.homebase.api.client.payload.PayloadReader.videoAccess]; a fake stands in for it in tests. */
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

    /**
     * The whole payload streamed to [outputPath], never held in memory, for [resolveVideoContent]'s non-segmented
     * MP4s; false on 404.
     */
    suspend fun streamPayloadDecryptedToPath(
        driveId: Uuid,
        fileId: Uuid,
        key: String,
        keyHeader: KeyHeader,
        outputPath: String,
        onProgress: ((Float) -> Unit)? = null,
    ): Boolean
}
