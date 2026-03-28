package id.homebase.api.video

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.uuid.Uuid

class VideoPreloader(
    private val driveFileProvider: DriveFileProvider,
    private val fileOperationsProvider: FileOperationsProvider,
) {
    private val fileSystem = FileSystem.SYSTEM
    private val mutexMap = mutableMapOf<String, Mutex>()
    private val mapLock = Mutex()

    private val preloadDir get() = "${fileOperationsProvider.getCacheDirectory()}/hbvid_preload"
    private fun cacheKey(fileId: Uuid, payloadKey: String) = "$fileId/$payloadKey"

    /**
     * Downloads the encrypted video payload into the cache so that [awaitPreloadedFile] can
     * unblock the tap handler immediately when play is pressed.
     *
     * Decryption is deferred to playback — the player calls [DriveFileProvider.getPayloadBytesDecrypted]
     * which hits the warm cache and decrypts in memory only.
     *
     * Safe to call multiple times — duplicate in-progress calls are no-ops.
     * HLS streams are skipped; their segments are fetched on demand by the player.
     */
    suspend fun preload(data: VideoPlayerData, onProgress: ((Float) -> Unit)? = null) {
        val key = cacheKey(data.fileId, data.payloadKey)
        val mutex = mapLock.withLock { mutexMap.getOrPut(key) { Mutex() } }
        if (!mutex.tryLock()) return
        try {
            val stubMetadata = data.descriptorContent?.let {
                OdinSystemSerializer.deserialize<VideoMetadata>(it)
            } ?: return

            if (stubMetadata.isSegmented) return  // HLS: segments are streamed on demand

            driveFileProvider.prefetchPayload(
                driveId = data.driveId,
                fileId = data.fileId,
                key = data.payloadKey,
                onDownloadProgress = onProgress,
            )
            Logger.d(tag = "VideoIO") { "preload complete (encrypted cache): ${data.fileId}/${data.payloadKey}" }
        } catch (e: Exception) {
            Logger.w(tag = "VideoIO") { "preload failed for ${data.fileId}/${data.payloadKey}: ${e.message}" }
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Waits for any in-progress preload to finish before returning, so the tap handler can
     * call [DriveFileProvider.getPayloadBytesDecrypted] knowing the encrypted bytes are cached.
     *
     * Always returns null — decryption now happens at playback time, not during preload.
     */
    suspend fun awaitPreloadedFile(fileId: Uuid, payloadKey: String): String? {
        val key = cacheKey(fileId, payloadKey)
        val mutex = mapLock.withLock { mutexMap.getOrPut(key) { Mutex() } }
        mutex.withLock {}  // wait for any in-progress download to finish
        return null
    }

    /** Deletes any legacy pre-decrypted files written by older builds. */
    fun clearCache() {
        try {
            fileSystem.deleteRecursively(preloadDir.toPath())
        } catch (e: Exception) {
            Logger.w(tag = "VideoIO") { "VideoPreloader.clearCache failed: ${e.message}" }
        }
    }
}
