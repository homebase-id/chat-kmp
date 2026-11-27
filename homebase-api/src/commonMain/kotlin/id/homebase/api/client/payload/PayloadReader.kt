package id.homebase.api.client.payload

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.BytesResponse
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.peer.PeerFileByGlobalTransitProvider
import id.homebase.api.common.OdinId
import id.homebase.api.file.FileOperationsProvider
import kotlinx.coroutines.flow.onEach
import kotlin.uuid.Uuid

/** Where a payload is read from: our own drive by file id, or a followed identity's drive by global transit id. */
sealed interface PayloadLocation {
    val driveId: Uuid

    data class Own(override val driveId: Uuid, val fileId: Uuid) : PayloadLocation

    /** [driveId] is the author's drive, which has no file of ours to name. */
    data class Peer(val peer: OdinId, override val driveId: Uuid, val globalTransitId: Uuid) : PayloadLocation

    companion object {
        fun of(driveId: Uuid, fileId: Uuid, remoteOdinId: OdinId?, globalTransitId: Uuid?): PayloadLocation =
            if (remoteOdinId != null && globalTransitId != null) Peer(remoteOdinId, driveId, globalTransitId) else Own(driveId, fileId)
    }
}

/** Reads a payload from our own drive or a followed identity's: the one place that tells the two apart. Exports and a few own-drive reads still call DriveFileProvider directly. */
class PayloadReader(
    private val own: DriveFileProvider,
    private val peer: PeerFileByGlobalTransitProvider,
    private val fileOps: FileOperationsProvider,
) {
    /** The payload, or the range of it, decrypted; null when it isn't there. */
    suspend fun bytes(
        at: PayloadLocation,
        key: String,
        keyHeader: KeyHeader,
        chunkStart: Long? = null,
        chunkLength: Long? = null,
        onProgress: ((Float) -> Unit)? = null,
    ): BytesResponse? = when (at) {
        is PayloadLocation.Own -> own.getPayloadBytesDecrypted(at.driveId, at.fileId, key, keyHeader, chunkStart, chunkLength, onProgress)
        is PayloadLocation.Peer -> if (chunkStart == null && chunkLength == null) {
            peer.getPayloadOverPeerByGlobalTransitId(at.peer, at.driveId, at.globalTransitId, key, keyHeader)
        } else {
            peer.getPayloadChunkOverPeerByGlobalTransitId(at.peer, at.driveId, at.globalTransitId, key, keyHeader, chunkStart ?: 0L, chunkLength)
        }
    }

    suspend fun thumb(
        at: PayloadLocation,
        key: String,
        keyHeader: KeyHeader,
        width: Int,
        height: Int,
        lastModified: Long? = null,
    ): BytesResponse? = when (at) {
        is PayloadLocation.Own -> own.getThumbBytesDecrypted(at.driveId, at.fileId, key, keyHeader, width, height, lastModified)
        is PayloadLocation.Peer -> peer.getThumbOverPeerByGlobalTransitId(at.peer, at.driveId, at.globalTransitId, key, width, height, keyHeader)
    }

    /** A range of the payload as the server sends it, still encrypted, for a player that decrypts it itself. */
    suspend fun encryptedChunk(at: PayloadLocation, key: String, chunkStart: Long, chunkLength: Long): ByteArray? = when (at) {
        is PayloadLocation.Own -> own.getPayloadBytesEncryptedChunk(at.driveId, at.fileId, key, chunkStart, chunkLength)
        is PayloadLocation.Peer -> peer.getPayloadChunkEncryptedOverPeerByGlobalTransitId(at.peer, at.driveId, at.globalTransitId, key, chunkStart, chunkLength)
    }

    /** The player's read of a whole payload into [outputPath]; false when it isn't there. */
    suspend fun playToPath(
        at: PayloadLocation,
        key: String,
        keyHeader: KeyHeader,
        outputPath: String,
        onProgress: ((Float) -> Unit)? = null,
    ): Boolean = when (at) {
        is PayloadLocation.Own -> own.playPayloadToPath(at.driveId, at.fileId, key, keyHeader, outputPath, onProgress)
        is PayloadLocation.Peer -> {
            var wroteAnything = false
            val found = peer.streamPayloadDecrypted(at.peer, at.driveId, at.globalTransitId, key, keyHeader, onProgress) { bytes ->
                fileOps.writeDownload(outputPath, bytes.onEach { if (it.isNotEmpty()) wroteAnything = true })
            }
            if (!found || !wroteAnything) {
                Logger.w(tag = "VideoIO") { "peer stream produced no bytes for ${at.peer}/${at.globalTransitId}/$key" }
            }
            found && wroteAnything
        }
    }

    // The preloader reads only our own drive: a peer's payload is fetched when it plays.
    suspend fun prefetch(at: PayloadLocation, key: String, onProgress: ((Float) -> Unit)? = null) {
        if (at is PayloadLocation.Own) own.prefetchPayload(at.driveId, at.fileId, key, onProgress)
    }

    suspend fun prefetchChunk(at: PayloadLocation, key: String, chunkStart: Long, chunkLength: Long, onProgress: ((Float) -> Unit)? = null) {
        if (at is PayloadLocation.Own) own.prefetchPayloadChunk(at.driveId, at.fileId, key, chunkStart, chunkLength, onProgress)
    }
}
