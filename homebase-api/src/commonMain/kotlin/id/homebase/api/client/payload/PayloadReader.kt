package id.homebase.api.client.payload

import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.BytesResponse
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ResendPayloadByteSource
import id.homebase.api.client.peer.PeerFileByGlobalTransitProvider
import id.homebase.api.common.OdinId
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.video.VideoCompressionService
import id.homebase.api.video.isHlsVideo
import kotlinx.coroutines.flow.Flow
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

/** Every payload read, from our own drive or a followed identity's; the temporal drive API is the one read around it. */
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

    /** The whole payload, decrypted, to [consume] as it streams in, at any size; false when it isn't there. [cache] as for [toFile]. */
    suspend fun stream(
        at: PayloadLocation,
        key: String,
        keyHeader: KeyHeader,
        cache: Boolean,
        onProgress: ((Float) -> Unit)? = null,
        lastModified: Long? = null,
        consume: suspend (Flow<ByteArray>) -> Unit,
    ): Boolean = when (at) {
        is PayloadLocation.Own -> own.streamPayloadDecrypted(at.driveId, at.fileId, key, keyHeader, onProgress, cache, lastModified, consume)
        is PayloadLocation.Peer -> peer.streamPayloadDecrypted(at.ref.odinId, at.driveId, at.ref.globalTransitId, key, keyHeader, onProgress, lastModified, consume)
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
        lastModified: Long? = null,
    ): Boolean = when (at) {
        is PayloadLocation.Own -> own.streamPayloadDecryptedToPath(at.driveId, at.fileId, key, keyHeader, outputPath, fileOps, onProgress, cache, lastModified)
        is PayloadLocation.Peer -> {
            var wroteAnything = false
            val found = peer.streamPayloadDecrypted(at.ref.odinId, at.driveId, at.ref.globalTransitId, key, keyHeader, onProgress, lastModified) { bytes ->
                fileOps.writeDownload(outputPath, bytes.onEach { if (it.isNotEmpty()) wroteAnything = true })
            }
            if (!found || !wroteAnything) {
                Logger.w(tag = "PayloadIO") { "peer stream produced no bytes for ${at.ref.odinId}/${at.ref.globalTransitId}/$key" }
            }
            found && wroteAnything
        }
    }

    /**
     * A received HLS video, downloaded and remuxed into an MP4 at [outputPath] (a file, or a `content://` entry the reader's
     * file operations can write). False, logged, when the payload isn't there or isn't H.264/AAC in MPEG-TS; a file at
     * [outputPath] is then deleted, a `content://` entry is the caller's to discard. A failed download, decrypt or write throws.
     */
    suspend fun downloadHlsAsMp4(
        at: PayloadLocation,
        payloadKey: String,
        keyHeader: KeyHeader,
        outputPath: String,
        cache: Boolean,
        lastModified: Long? = null,
    ): Boolean {
        val label = "payload $payloadKey at $at"
        var remuxed = false
        val found = stream(at, payloadKey, keyHeader, cache, lastModified = lastModified) { ts ->
            remuxed = VideoCompressionService.remuxTsToMp4(fileOps, ts, outputPath, label)
        }
        if (!found) Logger.e(tag = "HlsSave") { "download/decrypt failed: $label" }
        return found && remuxed
    }

    /**
     * This payload decrypted to [outputPath] as a file people can open: an HLS video remuxed into an MP4
     * ([downloadHlsAsMp4]), anything else as it is. False when it isn't there or can't be remuxed. [cache] as for
     * [toFile].
     */
    suspend fun writePlayable(
        at: PayloadLocation,
        payload: PayloadDescriptor,
        keyHeader: KeyHeader,
        outputPath: String,
        cache: Boolean,
    ): Boolean {
        // Only a kept read is versioned: the player, preloader and seeds keep payloads under the plain key, which a save reuses.
        val version = payload.lastModified.takeIf { cache }
        return if (payload.isHlsVideo) {
            downloadHlsAsMp4(at, payload.key, keyHeader, outputPath, cache, version)
        } else {
            toFile(at, payload.key, keyHeader, outputPath, cache, lastModified = version)
        }
    }

    /** Our own payloads and thumbs as stored, still encrypted, for a resend to re-attach. */
    fun resendSource(): ResendPayloadByteSource = object : ResendPayloadByteSource {
        override suspend fun storedPayloadBytes(driveId: Uuid, fileId: Uuid, key: String): ByteArray? =
            own.getPayloadBytesEncrypted(driveId, fileId, key)

        override suspend fun storedThumbBytes(
            driveId: Uuid, fileId: Uuid, payloadKey: String, width: Int, height: Int, lastModified: Long?,
        ): ByteArray? = own.getThumbBytesEncrypted(driveId, fileId, payloadKey, width, height, lastModified)
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
