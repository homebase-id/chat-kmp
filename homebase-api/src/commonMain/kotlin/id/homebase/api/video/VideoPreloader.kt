package id.homebase.api.video

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.file.FileOperationsProvider
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
    private fun stablePath(fileId: Uuid, payloadKey: String) = "$preloadDir/$fileId/$payloadKey/video.mp4".toPath()

    /**
     * Pre-fetches, decrypts, and writes the video to a stable on-disk path so [awaitPreloadedFile]
     * can hand the path directly to the player, skipping all IO on tap.
     *
     * Safe to call multiple times for the same fileId/payloadKey — duplicate calls while a preload
     * is in-progress are no-ops. Only Mp4 videos are preloaded; HLS streams on demand.
     */
    suspend fun preload(data: VideoPlayerData) {
        val key = cacheKey(data.fileId, data.payloadKey)
        val mutex = mapLock.withLock { mutexMap.getOrPut(key) { Mutex() } }
        if (!mutex.tryLock()) return  // already in-progress or being awaited
        try {
            val path = stablePath(data.fileId, data.payloadKey)
            if (fileSystem.exists(path)) return  // already preloaded

            val content = resolveVideoContent(data, driveFileProvider)
            if (content is VideoContent.Mp4) {
                fileSystem.createDirectories(path.parent!!)
                fileSystem.write(path) { write(content.bytes) }
                Logger.d(tag = "VideoIO") { "preload complete: ${data.fileId}/${data.payloadKey} (${content.bytes.size} bytes)" }
            }
            // HLS streams chunks on demand — nothing to preload
        } catch (e: Exception) {
            Logger.w(tag = "VideoIO") { "preload failed for ${data.fileId}/${data.payloadKey}: ${e.message}" }
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Returns the pre-written file path for this fileId + payloadKey, or null if the preload has
     * not completed.
     *
     * If a preload is currently in-flight this call suspends until it finishes, then returns the
     * path (or null if it failed / was HLS). This gives the tap handler the best chance of
     * skipping redundant IO without starting a parallel fetch.
     */
    suspend fun awaitPreloadedFile(fileId: Uuid, payloadKey: String): String? {
        val key = cacheKey(fileId, payloadKey)
        val mutex = mapLock.withLock { mutexMap.getOrPut(key) { Mutex() } }
        return mutex.withLock {
            val path = stablePath(fileId, payloadKey)
            if (fileSystem.exists(path)) path.toString() else null
        }
    }

    /** Deletes all pre-written files. Call alongside DriveFileProviderCached.clearCaches. */
    fun clearCache() {
        try {
            fileSystem.deleteRecursively(preloadDir.toPath())
        } catch (e: Exception) {
            Logger.w(tag = "VideoIO") { "VideoPreloader.clearCache failed: ${e.message}" }
        }
    }
}
