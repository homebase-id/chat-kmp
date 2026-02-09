package id.homebase.chat.widget.video

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.video.VideoMetadata
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.toString
import kotlin.uuid.Uuid

class VideoPlaybackPreparer(
    private val driveFileProvider: DriveFileProvider
) {
    suspend fun prepareVideoContentForPlayback(
        driveId: Uuid,
        fileId: Uuid,
        payloadKey: String,
        keyHeader: KeyHeader
    ): VideoPlaybackPreparationResult = withContext(Dispatchers.Default) {
        try {

            val header = driveFileProvider.getFileHeader(driveId, fileId)
                ?: throw Exception("No header found for file")

            val payloadDescriptor = header.fileMetadata.getPayloadDescriptor(payloadKey)
                ?: throw Exception("No descriptor content found in payload")

            val playlistContent =
                payloadDescriptor.descriptorContent
                    ?: throw Exception("No descriptor content found in payload")

            var videoMetaData = OdinSystemSerializer.deserialize<VideoMetadata>(playlistContent)

            if (!videoMetaData.isDescriptorContentComplete) {

                // get more info from the dedicated payload
                val payloadResponse = driveFileProvider.getPayloadBytesDecrypted(
                    driveId,
                    fileId,
                    payloadKey,
                    keyHeader
                )
                    ?: throw Exception("No descriptor content found in payload")
                val json = payloadResponse.bytes.decodeToString()

                videoMetaData = OdinSystemSerializer.deserialize<VideoMetadata>(json)
            }

            //
            // HLS
            //
            val isHls = videoMetaData.isSegmented && (
                    (videoMetaData.hlsPlaylist != null && videoMetaData.key == null) ||
                            (videoMetaData.hlsPlaylist == null && videoMetaData.key != null)
                    )

            if (isHls) {

//                if (videoMetaData.hlsPlaylist == null) {
//                    val videoMetaDataPayload =
//                        videoPayload.headerWrapper.getPayloadWrapper(videoMetaData.key!!)
//                    val json =
//                        videoMetaDataPayload.getPayloadBytes(appOrOwner).decodeToString()
//                    videoMetaData = OdinSystemSerializer.deserialize<VideoMetadata>(json)
//                }

                Logger.i("VideoPreparer") { "Preparing HLS video playback" }

                val hlsPlayList = createHlsPlaylist(
                    driveId,
                    fileId,
                    payloadKey,
                    keyHeader,
                    videoMetaData
                )

                Logger.i("VideoPreparer") { "HLS patched playlist:\n $hlsPlayList" }

                val serverUrl = videoServer.getServerUrl()
                val contentId = "video-manifest-${fileId}=${payloadKey}.m3u8"

                val proxiedPlayList =
                    hlsPlayList.lines().joinToString("\n") { line ->
                        if (line.startsWith("https://")) {
                            val encodedUrl = line.encodeURLParameter()
                            "$serverUrl/proxy?url=$encodedUrl&manifestId=$contentId"
                        } else {
                            line
                        }
                    }

                Logger.i("VideoPreparer") { "HLS proxied playlist:\n $proxiedPlayList" }

                videoServer.registerContent(
                    id = contentId,
                    data = proxiedPlayList.encodeToByteArray(),
                    contentType = "application/vnd.apple.mpegurl",
                    authTokenHeaderName = cookieNameFrom(appOrOwner),
                    authToken = videoPayload.authenticated.clientAuthToken
                )

                return@withContext VideoPlaybackPreparationResult.Success(
                    url = videoServer.getContentUrl(contentId),
                    contentId = contentId
                )
            }

            //
            // Non-HLS
            //
            else {
                //TODO: consider streaming
                val videoBytes = driveFileProvider
                    .getPayloadBytesDecrypted(driveId, fileId, payloadKey, keyHeader)

                val contentId = "video-${Uuid.random()}"

                videoServer.registerContent(
                    id = contentId,
                    data = videoBytes,
                    contentType = "video/mp4",
                    authTokenHeaderName = cookieNameFrom(appOrOwner),
                    authToken = videoPayload.authenticated.clientAuthToken
                )

                return@withContext VideoPlaybackPreparationResult.Success(
                    url = videoServer.getContentUrl(contentId),
                    contentId = contentId
                )
            }

        } catch (e: Exception) {
            Logger.e("VideoPreparer", e) { "Failed to prepare video" }
            return@withContext VideoPlaybackPreparationResult.Error(
                e.message ?: "Unknown error"
            )
        }
    }

    fun unprepareVideoContent(contentId: String) {
        videoServer.unregisterContent(contentId)
    }

    private suspend fun createHlsPlaylist(
        driveId: Uuid,
        fileId: Uuid,
        payloadKey: String,
        keyHeader: KeyHeader,
        videoMetaData: VideoMetadata
    ): String {

        if (!videoMetaData.isSegmented) {
            throw Exception("Video is not segmented; HLS playlist cannot be created")
        }

        if (videoMetaData.hlsPlaylist == null) {
            throw Exception("Insufficient data to create HLS playlist")
        }

        var lines = videoMetaData.hlsPlaylist!!.lines()
        if (lines.isEmpty() || !lines[0].startsWith("#EXTM3U")) {
            throw Exception("Invalid HLS playlist content")
        }

        lines = patchHlsUrls(
            driveId,
            fileId,
            payloadKey,
            keyHeader,
            lines
        )
        lines = fixTargetDuration(lines)
        lines = convertByteRangesToUrlPath(lines)

        return lines.joinToString("\n")
    }

    private suspend fun patchHlsUrls(
        driveId: Uuid,
        fileId: Uuid,
        payloadKey: String,
        keyHeader: KeyHeader,
        lines: List<String>
    ): List<String> {

        val modifiedLines = ArrayList<String>(lines.size)
        val aesKey = keyHeader.aesKey.unsafeBytes

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-KEY:METHOD=AES-128") -> {
                    if (aesKey == null) {
                        throw Exception("AES key is null but playlist requires encryption key")
                    }
                    val uriRegex = Regex("""URI="([^"]+)"""")
                    val match = uriRegex.find(line)
                    if (match != null && match.groupValues.size > 1) {
                        val originalKeyUri = match.groupValues[1]
                        val newKeyUri =
                            "data:application/octet-stream;base64,$aesKey"
                        modifiedLines.add(line.replace(originalKeyUri, newKeyUri))
                    }
                }

                !line.startsWith("#") && line.isNotBlank() -> {
                    val newUrl = driveFileProvider.getPayloadUrl(driveId, fileId, payloadKey)
//                    val newUrl = videoPayload.getEncryptedPayloadUri(appOrOwner)
                    modifiedLines.add(newUrl)
                }

                else -> modifiedLines.add(line)
            }
        }

        return modifiedLines
    }

    private fun fixTargetDuration(lines: List<String>): List<String> {
        val maxDuration = lines.asSequence()
            .filter { it.startsWith("#EXTINF:") }
            .mapNotNull {
                it.substringAfter(":")
                    .substringBefore(",")
                    .toDoubleOrNull()
            }
            .maxOrNull() ?: 0.0

        val newTarget = ceil(maxDuration).toInt()

        return lines.map { line ->
            if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                "#EXT-X-TARGETDURATION:$newTarget"
            } else {
                line
            }
        }
    }

    private fun convertByteRangesToUrlPath(lines: List<String>): List<String> {
        val result = mutableListOf<String>()
        var pendingRange: Pair<String, String>? = null

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    val rawValue = line.substringAfter(":")
                    val parts = rawValue.split("@")
                    val length = parts[0]
                    val offset = parts.getOrNull(1) ?: "0"
                    pendingRange = offset to length
                }

                line.isNotBlank() && !line.startsWith("#") -> {
                    if (pendingRange != null) {
                        val (offset, length) = pendingRange
                        val cleanBaseUrl = line.substringBefore("?")
                        result.add("$cleanBaseUrl/$offset/$length")
                        pendingRange = null
                    } else {
                        result.add(line)
                    }
                }

                else -> result.add(line)
            }
        }
        return result
    }
}
