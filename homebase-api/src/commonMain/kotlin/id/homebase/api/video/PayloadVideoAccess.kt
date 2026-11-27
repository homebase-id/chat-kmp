package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.payload.PayloadLocation
import id.homebase.api.client.payload.PayloadReader
import kotlin.uuid.Uuid

/** The video access the player and preloader read [at] through; the ids each call passes name the same video. */
fun PayloadReader.videoAccess(at: PayloadLocation): VideoPrefetchDriveAccess = object : VideoPrefetchDriveAccess {
    override suspend fun prefetchPayload(driveId: Uuid, fileId: Uuid, key: String, onDownloadProgress: ((Float) -> Unit)?) =
        prefetch(at, key, onDownloadProgress)

    override suspend fun prefetchPayloadChunk(
        driveId: Uuid,
        fileId: Uuid,
        key: String,
        chunkStart: Long,
        chunkLength: Long,
        onDownloadProgress: ((Float) -> Unit)?,
    ) = prefetchChunk(at, key, chunkStart, chunkLength, onDownloadProgress)

    override suspend fun getPayloadBytesDecrypted(
        driveId: Uuid,
        fileId: Uuid,
        key: String,
        keyHeader: KeyHeader,
        chunkStart: Long?,
        chunkLength: Long?,
        onDownloadProgress: ((Float) -> Unit)?,
    ) = bytes(at, key, keyHeader, chunkStart, chunkLength, onDownloadProgress)

    override suspend fun streamPayloadDecryptedToPath(
        driveId: Uuid,
        fileId: Uuid,
        key: String,
        keyHeader: KeyHeader,
        outputPath: String,
        onProgress: ((Float) -> Unit)?,
    ) = playToPath(at, key, keyHeader, outputPath, onProgress)
}
