package id.homebase.chat.viewonce

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.coroutines.supervisedScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * The only reader of a view-once payload. Bytes come over the network and are decrypted in
 * memory; the disk payload and chunk caches are never written for this file.
 */
class ViewOncePayloadLoader(
    private val driveFileProvider: DriveFileProvider,
    private val evictDecodedImage: suspend (driveId: Uuid, fileId: Uuid) -> Unit,
) {
    private val scope = supervisedScope("view-once-loader")

    // Touched only from the main thread (composition and its effects), so a plain set is enough.
    private val consumed = mutableSetOf<Uuid>()

    /** The item was shown and is spent: nothing may load it again in this process, whatever viewer asks. */
    fun markConsumed(fileId: Uuid) {
        consumed += fileId
    }

    fun isConsumed(fileId: Uuid): Boolean = fileId in consumed

    /**
     * Takes one hold on the file's cache bypass; pair every successful call with one [evict].
     * Call before any reader (video playback included) touches the file.
     */
    suspend fun begin(fileId: Uuid) {
        if (isConsumed(fileId)) throw ViewOnceAlreadyConsumedException()
        driveFileProvider.markPayloadEphemeral(fileId)
    }

    suspend fun loadBytes(driveId: Uuid, fileId: Uuid, payloadKey: String, keyHeader: KeyHeader): ByteArray {
        if (isConsumed(fileId)) throw ViewOnceAlreadyConsumedException()
        return driveFileProvider
            .getPayloadBytesDecryptedFromNetwork(driveId, fileId, payloadKey, keyHeader)
            .bytes
    }

    suspend fun evict(driveId: Uuid, fileId: Uuid, payloadKey: String) {
        driveFileProvider.evictFile(driveId, fileId, payloadKey)
        evictDecodedImage(driveId, fileId)
    }

    /** For a viewer leaving composition: its own scope is gone, this one outlives it. */
    fun evictAsync(driveId: Uuid, fileId: Uuid, payloadKey: String) {
        scope.launch {
            try {
                evict(driveId, fileId, payloadKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w("ViewOnceLoader", e) { "evict failed for $fileId" }
            }
        }
    }
}

class ViewOnceAlreadyConsumedException : IllegalStateException("view-once item already consumed")
