package id.homebase.api.client.drives.files

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.PayloadSizePolicy
import id.homebase.api.client.PayloadTooLargeException
import id.homebase.api.file.FileOperationsProvider
import kotlin.uuid.Uuid

/**
 * Where an exported (streamed, decrypted) payload lands. Every destination is an
 * EXISTING swept location — exports add no new cleanup surface:
 *
 * - [ShareOutbound] — `<cacheDir>/share_outbound/` (cleartext handed to another
 *   app; reaped as a unit on cold start + foreground).
 * - [UploadTemp] — `<cacheDir>/upload-temp/` (disposable scratch; swept every
 *   startup / "Clear caches").
 * - [CacheRoot] — a prefixed file at the cacheDir root (`hbvid_res_*`,
 *   `hlsdl_*`-style; untracked → swept every startup).
 */
sealed interface ExportDestination {
    data class ShareOutbound(val suffix: String) : ExportDestination
    data class UploadTemp(val prefix: String, val suffix: String) : ExportDestination
    data class CacheRoot(val prefix: String, val suffix: String) : ExportDestination
}

/**
 * The single entry point for reading downloaded payloads (#845) — the
 * download-side counterpart of `UploadService`. Callers declare INTENT instead
 * of picking a read primitive, which is what keeps the render/export boundary
 * from eroding one call site at a time (the share-to-app OOM existed in three
 * independent copies precisely because each caller hand-rolled
 * "get bytes → write file"):
 *
 * - [renderBytes] — RENDER intent: the payload will be decoded/rendered in-app.
 *   Byte-array read, LRU-cached, guarded at [PayloadSizePolicy.RENDER_LIMIT_BYTES]
 *   (a [PayloadTooLargeException] propagates to the caller's fallback).
 * - [exportToTemp] — EXPORT intent: the payload is passing through to a file
 *   destination (save, share, external player). Streamed decrypt (~64 KB working
 *   set) at ANY size; never touches the LRU caches.
 *
 * Downloads are re-fetchable, therefore disposable: every destination is swept
 * scratch, never a durable location.
 */
class PayloadDownloadService(
    private val driveFileProvider: DriveFileProvider,
    private val fileOperationsProvider: FileOperationsProvider,
) {

    /**
     * Fetch a payload the app will render, decrypted, as bytes. Cache-admitted and
     * size-guarded; returns null on 404. Throws [PayloadTooLargeException] above
     * the render limit — callers with a file destination belong on [exportToTemp].
     */
    suspend fun renderBytes(
        driveId: Uuid,
        fileId: Uuid,
        key: String,
        keyHeader: KeyHeader,
    ): ByteArray? {
        val response = try {
            driveFileProvider.getPayloadBytesDecrypted(
                driveId = driveId,
                fileId = fileId,
                key = key,
                keyHeader = keyHeader,
                chunkStart = null,
                chunkLength = null,
                onDownloadProgress = null,
            )
        } catch (e: NotFoundException) {
            return null
        } ?: return null
        return response.bytes
    }

    /**
     * Stream-decrypt a payload into a reserved path at [destination] and return
     * the absolute path, or null when the payload 404s. Bounded memory for any
     * payload size; bypasses the LRU caches entirely.
     */
    suspend fun exportToTemp(
        driveId: Uuid,
        fileId: Uuid,
        key: String,
        keyHeader: KeyHeader,
        destination: ExportDestination,
        onProgress: ((Float) -> Unit)? = null,
    ): String? {
        val path = when (destination) {
            is ExportDestination.ShareOutbound ->
                fileOperationsProvider.createShareOutboundPath(destination.suffix)
            is ExportDestination.UploadTemp ->
                fileOperationsProvider.createUploadTempPath(destination.prefix, destination.suffix)
            is ExportDestination.CacheRoot ->
                fileOperationsProvider.getCacheDirectory().trimEnd('/') +
                    "/" + destination.prefix + randomToken() + destination.suffix
        }
        val ok = driveFileProvider.streamPayloadDecryptedToPath(
            driveId = driveId,
            fileId = fileId,
            key = key,
            keyHeader = keyHeader,
            outputPath = path,
            fileOps = fileOperationsProvider,
            onProgress = onProgress,
        )
        if (!ok) {
            runCatching { fileOperationsProvider.deleteTempFile(path) }
            return null
        }
        return path
    }

    private fun randomToken(): String = kotlin.random.Random.nextLong().toULong().toString(16)
}
