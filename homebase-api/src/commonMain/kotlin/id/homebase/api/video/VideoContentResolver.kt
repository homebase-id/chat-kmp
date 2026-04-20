package id.homebase.api.video

import co.touchlab.kermit.Logger
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.time.measureTimedValue

sealed interface VideoContent {
    data class Hls(val metadata: VideoMetadata, val strippedPlaylist: String) : VideoContent
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
    return if (metadata.isSegmented && hlsPlaylist != null) {
        val strippedPlaylist = hlsPlaylist.lines()
            .filter { !it.startsWith("#EXT-X-KEY") }
            .joinToString("\n")
        VideoContent.Hls(metadata, strippedPlaylist)
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