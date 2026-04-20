package id.homebase.api.video

import co.touchlab.kermit.Logger
import id.homebase.api.file.FileOperationsProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.uuid.Uuid

class VideoPreloader(
    private val driveFileProvider: VideoPrefetchDriveAccess,
    private val fileOperationsProvider: FileOperationsProvider,
) {
    private val fileSystem = FileSystem.SYSTEM
    private val mutexMap = mutableMapOf<String, Mutex>()
    private val progressMap = mutableMapOf<String, MutableStateFlow<Float>>()
    private val mapLock = Mutex()

    private val preloadDir get() = "${fileOperationsProvider.getCacheDirectory()}/hbvid_preload"
    private fun cacheKey(fileId: Uuid, payloadKey: String) = "$fileId/$payloadKey"

    /**
     * Live preload progress (0f..1f) for this file's first HLS segment or full MP4 payload.
     * Subscribers (e.g. the full-screen player opened mid-prefetch) observe this to show real
     * bytes-downloaded progress instead of synthetic stage markers.
     */
    suspend fun progressFlow(fileId: Uuid, payloadKey: String): StateFlow<Float> {
        val key = cacheKey(fileId, payloadKey)
        return mapLock.withLock { progressMap.getOrPut(key) { MutableStateFlow(0f) } }.asStateFlow()
    }

    /**
     * Downloads the encrypted video payload into the cache so that [awaitPreloadedFile] can
     * unblock the tap handler immediately when play is pressed.
     *
     * Decryption is deferred to playback — the player calls [DriveFileProvider.getPayloadBytesDecrypted]
     * which hits the warm cache and decrypts in memory only.
     *
     * Concurrent calls for the same (fileId, payloadKey) serialize on a per-key mutex: the second
     * caller waits for the first to finish and then runs (hitting the disk cache, so it's a cheap
     * no-op unless the first call was cancelled mid-download). This matters when the surface
     * awaits preload() right as MediaItem's preload is unwinding from cancellation — a tryLock
     * race would leave the surface with no real progress to show.
     *
     * For HLS, only the first segment is pre-cached (encrypted, on disk); later segments are
     * fetched on demand by the player.
     */
    suspend fun preload(data: VideoPlayerData, onProgress: ((Float) -> Unit)? = null) {
        val key = cacheKey(data.fileId, data.payloadKey)
        val (mutex, progressSink) = mapLock.withLock {
            mutexMap.getOrPut(key) { Mutex() } to progressMap.getOrPut(key) { MutableStateFlow(0f) }
        }
        val emit: (Float) -> Unit = { v ->
            Logger.d(tag = "VideoIO") { "preload emit: ${data.fileId}/${data.payloadKey} v=$v" }
            progressSink.value = v
            onProgress?.invoke(v)
        }
        mutex.withLock {
            try {
                val metadata = try {
                    resolveVideoMetadata(data, driveFileProvider)
                } catch (e: Exception) {
                    Logger.d(tag = "VideoIO") { "preload skipped — no metadata for ${data.fileId}/${data.payloadKey}: ${e.message}" }
                    return@withLock
                }

                if (metadata.isSegmented) {
                    preloadFirstHlsSegment(data, metadata, emit)
                } else {
                    driveFileProvider.prefetchPayload(
                        driveId = data.driveId,
                        fileId = data.fileId,
                        key = data.payloadKey,
                        onDownloadProgress = emit,
                    )
                    Logger.d(tag = "VideoIO") { "preload complete (mp4, encrypted cache): ${data.fileId}/${data.payloadKey}" }
                }
                emit(1f)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(tag = "VideoIO") { "preload failed for ${data.fileId}/${data.payloadKey}: ${e.message}" }
            }
        }
    }

    private suspend fun preloadFirstHlsSegment(
        data: VideoPlayerData,
        metadata: VideoMetadata,
        onProgress: (Float) -> Unit,
    ) {
        val playlist = metadata.hlsPlaylist
        if (playlist == null) {
            Logger.d(tag = "VideoIO") { "hls preload skipped — no playlist in metadata for ${data.fileId}/${data.payloadKey}" }
            return
        }
        // FFmpeg always emits the explicit `<len>@<offset>` form for segment 0. The implicit
        // `<len>` form only appears for segments that follow another segment contiguously.
        val match = FIRST_BYTERANGE_REGEX.find(playlist)
        if (match == null) {
            Logger.d(tag = "VideoIO") { "hls preload skipped — no #EXT-X-BYTERANGE in playlist for ${data.fileId}/${data.payloadKey}" }
            return
        }
        val length = match.groupValues[1].toLong()
        val offset = match.groupValues[2].toLong()

        Logger.d(tag = "VideoIO") { "hls preload seg0 start: fileId=${data.fileId} key=${data.payloadKey} offset=$offset length=$length" }
        driveFileProvider.prefetchPayloadChunk(
            driveId = data.driveId,
            fileId = data.fileId,
            key = data.payloadKey,
            chunkStart = offset,
            chunkLength = length,
            onDownloadProgress = onProgress,
        )
        Logger.d(tag = "VideoIO") { "hls preload seg0 complete: fileId=${data.fileId} key=${data.payloadKey} offset=$offset length=$length" }
    }

    private companion object {
        private val FIRST_BYTERANGE_REGEX = Regex("""#EXT-X-BYTERANGE:(\d+)@(\d+)""")
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
