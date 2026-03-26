package id.homebase.chat.widget.video

import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.video.VideoMetadata
import id.homebase.chat.conversationlist.FullScreenOverlay

internal sealed interface VideoContent {
    data class Hls(val metadata: VideoMetadata, val strippedPlaylist: String) : VideoContent
    data class Mp4(val metadata: VideoMetadata, val bytes: ByteArray) : VideoContent
}

internal suspend fun resolveVideoContent(
    data: FullScreenOverlay.VideoPlayerData,
    driveFileProvider: DriveFileProvider,
): VideoContent {
    val stubMetadata = data.payload.descriptorContent?.let {
        OdinSystemSerializer.deserialize<VideoMetadata>(it)
    } ?: error("Missing video metadata")

    val metadata = if (!stubMetadata.isDescriptorContentComplete) {
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

    val hlsPlaylist = metadata.hlsPlaylist
    return if (metadata.isSegmented && hlsPlaylist != null) {
        val strippedPlaylist = hlsPlaylist.lines()
            .filter { !it.startsWith("#EXT-X-KEY") }
            .joinToString("\n")
        VideoContent.Hls(metadata, strippedPlaylist)
    } else {
        val bytes = driveFileProvider.getPayloadBytesDecrypted(
            driveId = data.driveId,
            fileId = data.fileId,
            key = data.payloadKey,
            keyHeader = data.keyHeader,
        )?.bytes ?: error("Failed to download video")
        VideoContent.Mp4(metadata, bytes)
    }
}