package id.homebase.api.client.payload

import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.BytesResponse
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.peer.PeerFileByGlobalTransitProvider
import id.homebase.api.common.OdinId
import id.homebase.api.file.FileOperationsProvider
import kotlinx.coroutines.flow.onEach
import kotlin.uuid.Uuid

/** A followed identity's file, by its author and global transit id: the two travel together or not at all. */
@Immutable
data class PeerFileRef(val odinId: OdinId, val globalTransitId: Uuid)

/** Where a payload is read from: our own drive by file id, or a followed identity's drive by global transit id. */
sealed interface PayloadLocation {
    val driveId: Uuid

    data class Own(override val driveId: Uuid, val fileId: Uuid) : PayloadLocation

    /** [driveId] is the author's drive, which has no file of ours to name. */
    data class Peer(val ref: PeerFileRef, override val driveId: Uuid) : PayloadLocation

    companion object {
        fun of(driveId: Uuid, fileId: Uuid, peer: PeerFileRef?): PayloadLocation =
            if (peer != null) Peer(peer, driveId) else Own(driveId, fileId)
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
        lastModified: Long? = null,
    ): BytesResponse? = when (at) {
        is PayloadLocation.Own -> own.getPayloadBytesDecrypted(at.driveId, at.fileId, key, keyHeader, chunkStart, chunkLength, onProgress, lastModified)
        is PayloadLocation.Peer -> if (chunkStart == null && chunkLength == null) {
            peer.getPayloadOverPeerByGlobalTransitId(at.ref.odinId, at.driveId, at.ref.globalTransitId, key, keyHeader, onProgress, lastModified)
        } else {
            peer.getPayloadChunkOverPeerByGlobalTransitId(at.ref.odinId, at.driveId, at.ref.globalTransitId, key, keyHeader, chunkStart ?: 0L, chunkLength, onProgress, lastModified)
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
        is PayloadLocation.Peer -> peer.getThumbOverPeerByGlobalTransitId(at.ref.odinId, at.driveId, at.ref.globalTransitId, key, width, height, keyHeader, lastModified)
    }

    /** A range of the payload as the server sends it, still encrypted, for a player that decrypts it itself. */
    suspend fun encryptedChunk(at: PayloadLocation, key: String, chunkStart: Long, chunkLength: Long): ByteArray? = when (at) {
        is PayloadLocation.Own -> own.getPayloadBytesEncryptedChunk(at.driveId, at.fileId, key, chunkStart, chunkLength)
        is PayloadLocation.Peer -> peer.getPayloadChunkEncryptedOverPeerByGlobalTransitId(at.ref.odinId, at.driveId, at.ref.globalTransitId, key, chunkStart, chunkLength)
    }

    /**
     * The whole payload, decrypted, streamed into [outputPath] at any size; false when it isn't there. [cache] keeps our
     * own drive's copy for the next read (a player's replay); it applies to our own drive only.
     */
    suspend fun toFile(
        at: PayloadLocation,
        key: String,
        keyHeader: KeyHeader,
        outputPath: String,
        cache: Boolean,
        onProgress: ((Float) -> Unit)? = null,
    ): Boolean = when (at) {
        is PayloadLocation.Own -> own.streamPayloadDecryptedToPath(at.driveId, at.fileId, key, keyHeader, outputPath, fileOps, onProgress, cache)
        is PayloadLocation.Peer -> {
            var wroteAnything = false
            val found = peer.streamPayloadDecrypted(at.ref.odinId, at.driveId, at.ref.globalTransitId, key, keyHeader, onProgress) { bytes ->
                fileOps.writeDownload(outputPath, bytes.onEach { if (it.isNotEmpty()) wroteAnything = true })
            }
            if (!found || !wroteAnything) {
                Logger.w(tag = "VideoIO") { "peer stream produced no bytes for ${at.ref.odinId}/${at.ref.globalTransitId}/$key" }
            }
            found && wroteAnything
        }
    }

    /** For a caller holding no key header: the server returns the payload's, encrypted to this session. */
    suspend fun bytesWithServerKey(at: PayloadLocation.Own, key: String): ByteArray? =
        own.getPayloadBytesDecryptedViaResponseHeader(at.driveId, at.fileId, key)

    // The preloader reads only our own drive: a peer's payload is fetched when it plays.
    suspend fun prefetch(at: PayloadLocation, key: String, onProgress: ((Float) -> Unit)? = null) {
        if (at is PayloadLocation.Own) own.prefetchPayload(at.driveId, at.fileId, key, onProgress)
    }

    suspend fun prefetchChunk(at: PayloadLocation, key: String, chunkStart: Long, chunkLength: Long, onProgress: ((Float) -> Unit)? = null) {
        if (at is PayloadLocation.Own) own.prefetchPayloadChunk(at.driveId, at.fileId, key, chunkStart, chunkLength, onProgress)
    }
}
