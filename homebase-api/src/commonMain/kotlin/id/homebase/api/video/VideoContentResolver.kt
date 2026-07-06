package id.homebase.api.video

import co.touchlab.kermit.Logger
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.random.Random
import kotlin.time.measureTimedValue

sealed interface VideoContent {
    data class Hls(val metadata: VideoMetadata, val originalPlaylist: String) : VideoContent

    /**
     * Decrypted MP4 streamed to a disposable cacheDir temp (`hbvid_res_*` —
     * untracked, swept every startup; #845). Bounded RAM for any video size —
     * the previous bytes-carrying variant buffered the whole payload (~2×) in
     * memory, and a large foreign non-segmented MP4 could OOM the app. The
     * playing surface owns deleting [filePath] on dispose (the sweep is the
     * backstop).
     */
    data class Mp4File(val metadata: VideoMetadata, val filePath: String) : VideoContent

    /**
     * Web only (`preferBytes = true`): decrypted MP4 bytes for a Base64 object
     * URL — the wasm FS is RAM-backed, so a file temp buys nothing there. The
     * render-limit guard bounds it: an oversized MP4 throws
     * `PayloadTooLargeException` before the body is buffered.
     */
    data class Mp4Bytes(val metadata: VideoMetadata, val bytes: ByteArray) : VideoContent
}

/** Resolves the full [VideoMetadata], fetching the descriptor payload if the stub is incomplete.
 *  Fetches go through the payload cache, so later calls (including [resolveVideoContent]) are warm. */
suspend fun resolveVideoMetadata(
    data: VideoPlayerData,
    driveFileProvider: VideoPrefetchDriveAccess,
): VideoMetadata {
    val stubMetadata = data.descriptorContent?.let {
        OdinSystemSerializer.deserialize<VideoMetadata>(it)
    } ?: error("Missing video metadata")

    if (stubMetadata.isDescriptorContentComplete) return stubMetadata

    val (metadata, elapsed) = measureTimedValue {
        val json = driveFileProvider.getPayloadBytesDecrypted(
            driveId = data.driveId,
            fileId = data.fileId,
            key = stubMetadata.key,
            keyHeader = data.keyHeader,
        )?.bytes?.decodeToString() ?: error("Failed to fetch video metadata")
        try {
            OdinSystemSerializer.deserialize<VideoMetadata>(json)
        } catch (e: Exception) {
            error("Failed to deserialize video metadata for ${data.fileId}/${data.payloadKey}: ${json.take(200)}, cause=${e.message}")
        }
    }
    Logger.d(tag = "VideoIO") { "metadata fetch: $elapsed" }
    return metadata
}

suspend fun resolveVideoContent(
    data: VideoPlayerData,
    driveFileProvider: VideoPrefetchDriveAccess,
    fileOps: FileOperationsProvider? = null,
    preferBytes: Boolean = false,
    onDownloadProgress: ((Float) -> Unit)? = null,
): VideoContent {
    val metadata = resolveVideoMetadata(data, driveFileProvider)

    val hlsPlaylist = metadata.hlsPlaylist
    Logger.d(tag = "VideoIO") {
        "metadata: fileId=${data.fileId} key=${data.payloadKey} mimeType=${metadata.mimeType} isSegmented=${metadata.isSegmented} fileSize=${metadata.fileSize} duration=${metadata.duration} codec=${metadata.codec} hlsPlaylistChars=${hlsPlaylist?.length ?: 0}"
    }
    if (metadata.isSegmented && hlsPlaylist == null) {
        // Smoking-gun case: server says segmented but no playlist available locally.
        // We'd silently fall through to the MP4 branch and hand encrypted TS bytes to
        // an MP4 decoder, producing a black screen with no error.
        Logger.w(tag = "VideoIO") { "metadata: isSegmented=true but hlsPlaylist=null — falling through to MP4 branch will fail silently. fileId=${data.fileId} descriptorComplete=${metadata.isDescriptorContentComplete}" }
    }
    return if (metadata.isSegmented && hlsPlaylist != null) {
        Logger.d(tag = "VideoIO") {
            "metadata: hls path chosen — playlistChars=${hlsPlaylist.length}"
        }
        VideoContent.Hls(metadata, hlsPlaylist)
    } else if (preferBytes || fileOps == null) {
        // Web path (RAM-backed FS): bytes for a Base64 object URL, bounded by the
        // render-limit guard — PayloadTooLargeException propagates to the surface's
        // unplayable-message handling instead of OOM-ing the tab.
        val (bytes, payloadElapsed) = measureTimedValue {
            driveFileProvider.getPayloadBytesDecrypted(
                driveId = data.driveId,
                fileId = data.fileId,
                key = data.payloadKey,
                keyHeader = data.keyHeader,
                onDownloadProgress = onDownloadProgress,
            )?.bytes ?: error("Failed to download video")
        }
        Logger.d(tag = "VideoIO") { "resolveVideoContent total payload: ${bytes.size} bytes in $payloadElapsed" }
        VideoContent.Mp4Bytes(metadata, bytes)
    } else {
        // Stream-decrypt to a disposable cacheDir temp (#845): bounded RAM at any
        // size, LRU untouched, and the surfaces play from a file path — which they
        // already did, except they used to buffer the whole payload in RAM first
        // just to write it themselves.
        val outputPath = fileOps.getCacheDirectory().trimEnd('/') +
            "/hbvid_res_${Random.nextLong().toULong().toString(16)}.mp4"
        val (ok, payloadElapsed) = measureTimedValue {
            driveFileProvider.streamPayloadDecryptedToPath(
                driveId = data.driveId,
                fileId = data.fileId,
                key = data.payloadKey,
                keyHeader = data.keyHeader,
                outputPath = outputPath,
                onProgress = onDownloadProgress,
            )
        }
        if (!ok) error("Failed to download video")
        Logger.d(tag = "VideoIO") {
            "resolveVideoContent streamed to $outputPath (${fileOps.getFileSize(outputPath)} bytes) in $payloadElapsed"
        }
        VideoContent.Mp4File(metadata, outputPath)
    }
}