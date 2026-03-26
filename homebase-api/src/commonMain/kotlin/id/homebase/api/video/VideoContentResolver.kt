package id.homebase.api.video

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.time.measureTimedValue

sealed interface VideoContent {
    data class Hls(val metadata: VideoMetadata, val strippedPlaylist: String) : VideoContent
    data class Mp4(val metadata: VideoMetadata, val bytes: ByteArray) : VideoContent
}

suspend fun resolveVideoContent(
    data: VideoPlayerData,
    driveFileProvider: DriveFileProvider,
    onDownloadProgress: ((Float) -> Unit)? = null,
): VideoContent {
    val stubMetadata = data.descriptorContent?.let {
        OdinSystemSerializer.deserialize<VideoMetadata>(it)
    } ?: error("Missing video metadata")

    val (metadata, metadataElapsed) = measureTimedValue {
        if (!stubMetadata.isDescriptorContentComplete) {
            val json = driveFileProvider.getPayloadBytesDecrypted(
                driveId = data.driveId,
                fileId = data.fileId,
                key = stubMetadata.key,
                keyHeader = data.keyHeader,
            )?.bytes?.decodeToString() ?: error("Failed to fetch video metadata")
            OdinSystemSerializer.deserialize<VideoMetadata>(json)
        } else {
            stubMetadata
        }
    }
    if (!stubMetadata.isDescriptorContentComplete) {
        Logger.d(tag = "VideoIO") { "metadata fetch: $metadataElapsed" }
    }

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