package id.homebase.api.video

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.DescriptorContentV1
import id.homebase.api.file.AppCacheDirs
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

/**
 * [readPayloadText] reads another payload of the file with the video's key header: the current format's
 * playlist or an older stub's overflow payload, both written with the video payload's IV.
 */
suspend fun videoMetadataFrom(
    descriptorContent: String,
    payloadKey: String,
    contentType: String?,
    readPayloadText: suspend (key: String) -> String?,
): VideoMetadata? {
    if (DescriptorContentV1.isCurrentFormat(descriptorContent)) {
        val current = DescriptorContentV1.parseOrNull(descriptorContent) ?: run {
            Logger.w(tag = "VideoIO") { "video descriptor in an unknown version or kind: ${descriptorContent.take(200)}" }
            return null
        }
        val playlist = current.video?.playlistKey?.let { readPayloadText(it) ?: return null }
        return current.toVideoMetadata(payloadKey, contentType, playlist)
    }
    val stub = try {
        OdinSystemSerializer.deserialize<VideoMetadata>(descriptorContent)
    } catch (e: Exception) {
        Logger.w(tag = "VideoIO") { "video descriptor doesn't parse: ${descriptorContent.take(200)}, cause=${e.message}" }
        return null
    }
    if (stub.isDescriptorContentComplete) return stub
    val json = readPayloadText(stub.key) ?: return null
    return try {
        OdinSystemSerializer.deserialize<VideoMetadata>(json)
    } catch (e: Exception) {
        Logger.w(tag = "VideoIO") { "video metadata payload ${stub.key} doesn't parse: ${json.take(200)}, cause=${e.message}" }
        null
    }
}

/** Resolves the full [VideoMetadata]. Fetches go through the payload cache, so later calls (including [resolveVideoContent]) are warm. */
suspend fun resolveVideoMetadata(
    data: VideoPlayerData,
    driveFileProvider: VideoPrefetchDriveAccess,
): VideoMetadata {
    val content = data.descriptorContent ?: error("Missing video metadata")
    val (metadata, elapsed) = measureTimedValue {
        videoMetadataFrom(content, data.payloadKey, data.contentType) { key ->
            driveFileProvider.getPayloadBytesDecrypted(
                driveId = data.driveId,
                fileId = data.fileId,
                key = key,
                keyHeader = data.keyHeader,
            )?.bytes?.decodeToString()
        }
    }
    Logger.d(tag = "VideoIO") { "metadata: $elapsed" }
    return metadata ?: error("Failed to resolve video metadata for ${data.fileId}/${data.payloadKey}")
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
        val outputPath = AppCacheDirs.scratchPath(fileOps.getCacheDirectory(), data.scratchSub) +
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