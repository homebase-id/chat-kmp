package id.homebase.chat.viewonce

import co.touchlab.kermit.Logger
import coil3.ImageLoader
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.coroutines.ioDispatcher
import id.homebase.api.coroutines.supervisedScope
import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.util.isMobile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/** Streams the photo network-only to a temp file and holds the cache-bypass mark that video playback also relies on. */
class ViewOncePayloadLoader(
    private val driveFileProvider: DriveFileProvider,
    private val fileOps: FileOperationsProvider,
    private val tempDir: () -> String,
    private val canView: () -> Boolean = { isMobile() },
    private val evictLocalImage: (path: String) -> Unit = {},
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
        requireReadable(fileId)
        driveFileProvider.markPayloadEphemeral(fileId)
    }

    /** The decrypted photo as an app-private temp file; [deleteTempAsync] it on close, and the startup sweep reaps any a crash left. */
    suspend fun loadToTempFile(driveId: Uuid, fileId: Uuid, payloadKey: String, keyHeader: KeyHeader): String {
        requireReadable(fileId)
        val path = withContext(ioDispatcher) { tempDir() } + "/vo_" + Uuid.random().toHexString()
        try {
            val found = withContext(ioDispatcher) {
                driveFileProvider.streamPayloadDecryptedToPath(driveId, fileId, payloadKey, keyHeader, path, fileOps)
            }
            if (!found) throw NotFoundException()
        } catch (e: Throwable) {
            // A failed or cancelled write leaves a file whose path the caller never learns.
            deleteTempAsync(path)
            throw e
        }
        return path
    }

    fun deleteTempAsync(path: String) {
        evictLocalImage(path)
        scope.launch(ioDispatcher) {
            if (!fileOps.deleteTempFile(path)) Logger.w("ViewOnceLoader") { "temp delete failed" }
        }
    }

    private fun requireReadable(fileId: Uuid) {
        requireViewable(fileId)
        if (isConsumed(fileId)) throw ViewOnceAlreadyConsumedException()
    }

    // Desktop and web only ever show "Open on your phone"; a read reaching here is a bug, and it must not start the clock.
    private fun requireViewable(fileId: Uuid) {
        if (canView()) return
        Logger.e("ViewOnceLoader") { "refused view-once payload read on a non-mobile platform file=$fileId" }
        throw ViewOnceNotViewableHereException()
    }

    suspend fun evict(driveId: Uuid, fileId: Uuid, payloadKey: String) {
        driveFileProvider.evictFile(driveId, fileId, payloadKey)
        evictDecodedImage(driveId, fileId)
    }

    /** For a viewer leaving composition: its own scope is gone, this one outlives it. */
    fun evictAsync(driveId: Uuid, fileId: Uuid, payloadKey: String) {
        scope.launch { evict(driveId, fileId, payloadKey) }
    }
}

// Zoom tiles are cached under keys derived from the image's, so a substring match takes them too.
fun ImageLoader.evictMemoryFor(path: String) {
    val cache = memoryCache ?: return
    cache.keys.filter { path in it.key }.forEach(cache::remove)
}

class ViewOnceAlreadyConsumedException : IllegalStateException("view-once item already consumed")

class ViewOnceNotViewableHereException : IllegalStateException("view-once media can only be opened on a phone or tablet")
