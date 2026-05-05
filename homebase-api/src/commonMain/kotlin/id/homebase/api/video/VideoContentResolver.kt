package id.homebase.api.video

import co.touchlab.kermit.Logger
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.time.measureTimedValue

sealed interface VideoContent {
    data class Hls(val metadata: VideoMetadata, val originalPlaylist: String) : VideoContent
    data class Mp4(val metadata: VideoMetadata, val bytes: ByteArray) : VideoContent
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
    } else {
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
        VideoContent.Mp4(metadata, bytes)
    }
}