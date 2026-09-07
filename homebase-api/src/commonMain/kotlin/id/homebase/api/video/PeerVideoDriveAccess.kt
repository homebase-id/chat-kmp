package id.homebase.api.video

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.BytesResponse
import id.homebase.api.client.peer.PeerFileByGlobalTransitProvider
import id.homebase.api.common.OdinId
import id.homebase.api.file.FileOperationsProvider
import kotlinx.coroutines.flow.flow
import kotlin.uuid.Uuid

/**
 * Reads a followed identity's video off the AUTHOR's drive (CDN-first, peer fallback) behind the same
 * interface the local drive implements, so the three platform player surfaces keep one call shape and
 * only have to pick which access to hand [resolveVideoContent].
 *
 * [fileId] is ignored throughout: a followed post has no local file, and the author's copy is addressed
 * by globalTransitId instead.
 */
class PeerVideoDriveAccess(
    private val peer: OdinId,
    private val globalTransitId: Uuid,
    private val peerProvider: PeerFileByGlobalTransitProvider,
    private val fileOps: FileOperationsProvider,
) : VideoPrefetchDriveAccess {

    // The preloader is wired to the local drive provider, so peer prefetch would read the wrong identity.
    // Playback still warms the shared HLS chunk cache on its own; only the progress bar loses its source.
    override suspend fun prefetchPayload(
        driveId: Uuid,
        fileId: Uuid,
        key: String,
        onDownloadProgress: ((Float) -> Unit)?,
    ) = Unit

    override suspend fun prefetchPayloadChunk(
        driveId: Uuid,
        fileId: Uuid,
        key: String,
        chunkStart: Long,
        chunkLength: Long,
        onDownloadProgress: ((Float) -> Unit)?,
    ) = Unit

    override suspend fun getPayloadBytesDecrypted(
        driveId: Uuid,
        fileId: Uuid,
        key: String,
        keyHeader: KeyHeader,
        chunkStart: Long?,
        chunkLength: Long?,
        onDownloadProgress: ((Float) -> Unit)?,
    ): BytesResponse? = if (chunkStart == null && chunkLength == null) {
        peerProvider.getPayloadOverPeerByGlobalTransitId(
            peer = peer,
            driveId = driveId,
            globalTransitId = globalTransitId,
            payloadKey = key,
            keyHeader = keyHeader,
        )
    } else {
        peerProvider.getPayloadChunkOverPeerByGlobalTransitId(
            peer = peer,
            driveId = driveId,
            globalTransitId = globalTransitId,
            payloadKey = key,
            keyHeader = keyHeader,
            chunkStart = chunkStart ?: 0L,
            chunkLength = chunkLength,
        )
    }

    /**
     * Non-segmented MP4 path. Pulled in [STREAM_CHUNK_BYTES] slices so RAM stays bounded at any video size
     * — the whole-payload read is capped at the render limit and would refuse a real video outright.
     */
    override suspend fun streamPayloadDecryptedToPath(
        driveId: Uuid,
        fileId: Uuid,
        key: String,
        keyHeader: KeyHeader,
        outputPath: String,
        onProgress: ((Float) -> Unit)?,
    ): Boolean {
        var wroteAnything = false
        fileOps.writeStream(
            outputPath,
            flow {
                var offset = 0L
                while (true) {
                    val slice = peerProvider.getPayloadChunkOverPeerByGlobalTransitId(
                        peer = peer,
                        driveId = driveId,
                        globalTransitId = globalTransitId,
                        payloadKey = key,
                        keyHeader = keyHeader,
                        chunkStart = offset,
                        chunkLength = STREAM_CHUNK_BYTES,
                    ) ?: break
                    if (slice.bytes.isEmpty()) break
                    wroteAnything = true
                    emit(slice.bytes)
                    // A short read is the end of the file; the range routes clamp rather than 416.
                    if (slice.bytes.size < STREAM_CHUNK_BYTES) break
                    offset += slice.bytes.size
                }
            },
        )
        if (!wroteAnything) {
            Logger.w(tag = "VideoIO") { "peer stream produced no bytes for $peer/$globalTransitId/$key" }
        }
        return wroteAnything
    }

    private companion object {
        const val STREAM_CHUNK_BYTES = 4L * 1024 * 1024
    }
}

/**
 * The drive access this video should be read through: the author's, when the post came from someone we
 * follow, otherwise our own. Keeps the local-vs-peer branch in one place instead of once per platform
 * surface.
 */
fun VideoPlayerData.driveAccess(
    local: VideoPrefetchDriveAccess,
    peerProvider: PeerFileByGlobalTransitProvider,
    fileOps: FileOperationsProvider,
): VideoPrefetchDriveAccess {
    val peer = remoteOdinId
    val gtid = globalTransitId
    return if (peer != null && gtid != null) {
        PeerVideoDriveAccess(peer, gtid, peerProvider, fileOps)
    } else {
        local
    }
}
