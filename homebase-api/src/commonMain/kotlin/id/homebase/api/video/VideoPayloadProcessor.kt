package id.homebase.api.video

import id.homebase.api.HomebaseProtocol
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.createThumbnails
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.utils.io.core.toByteArray


class VideoPayloadProcessor(
    private val fileOperationsProvider: FileOperationsProvider,
) {
    private val FIVE_MB = 5L * 1024 * 1024

    suspend fun process(
        payload: PayloadFile,
        keyHeader: KeyHeader,
        onProgress: ((VideoPayloadProgressPhase) -> Unit)?,
        descriptorContentPayloadKey: String
    ): VideoProcessResult {

        // Resolve content URIs (Android) to real filesystem paths before FFmpeg work
        val resolvedPath = fileOperationsProvider.resolveToFilePath(payload.filePath)
        val payload =
            if (resolvedPath != payload.filePath) payload.copy(filePath = resolvedPath) else payload

        /* ---------- PHASE 1: THUMBNAILS ---------- */


        onProgress?.invoke(
            VideoPayloadProgressPhase(
                payload.key,
                VideoProcessingPhase.THUMBNAIL,
                0f
            )
        )

        var tinyThumb: EmbeddedThumb? = null
        var thumbnails: List<ThumbnailFile> = emptyList()

        val thumbnailPath = FFmpegUtils.grabThumbnail(payload.filePath)
        if (thumbnailPath != null) {
            try {
                val bytes = fileOperationsProvider.readFileBytes(thumbnailPath)
                val (_, generatedTinyThumb, generatedThumbnails) =
                    createThumbnails(bytes, payload.key)

                tinyThumb = generatedTinyThumb
                thumbnails = generatedThumbnails.map { thumb ->
                    thumb.copy(
                        thumbnailBytes = keyHeader.encryptDataAes(thumb.thumbnailBytes),
                    )
                }

            } finally {
                fileOperationsProvider.deleteTempFile(thumbnailPath)
            }
        }

        /* ---------- PHASE 2: COMPRESS (ALWAYS) ---------- */

        onProgress?.invoke(
            VideoPayloadProgressPhase(
                payload.key,
                VideoProcessingPhase.COMPRESSING,
                0f
            )
        )

        val compressedPath =
            FFmpegUtils.compressVideo(payload.filePath) {
                onProgress?.invoke(
                    VideoPayloadProgressPhase(
                        payload.key,
                        VideoProcessingPhase.COMPRESSING,
                        it
                    )
                )
            } ?: payload.filePath

        /* ---------- PHASE 3: SIZE CHECK → HLS DECISION ---------- */

        val compressedSize = fileOperationsProvider.getFileSize(compressedPath)
        val useHls = compressedSize >= FIVE_MB

        /* ---------- PHASE 4: SEGMENT (HLS ONLY) ---------- */

        val (playlistPath, videoPath, isSegmented) =
            if (useHls) {
                val (playlist, segments) =
                    FFmpegUtils.segmentAndEncryptVideo(
                        inputPath = compressedPath,
                        keyHeader = keyHeader,
                        onProgress = { pct ->
                            onProgress?.invoke(
                                VideoPayloadProgressPhase(
                                    payload.key,
                                    VideoProcessingPhase.SEGMENTING,
                                    pct
                                )
                            )
                        }
                    ) ?: error("segmentAndEncryptVideo failed")

                Triple(playlist, segments, true)
            } else {
                Triple(null, compressedPath, false)
            }

        /* ---------- PHASE 4.5: ENCRYPT NON-HLS VIDEO ---------- */

        val finalVideoPath =
            if (!isSegmented) {
                onProgress?.invoke(
                    VideoPayloadProgressPhase(
                        payload.key,
                        VideoProcessingPhase.ENCRYPTING,
                        0f
                    )
                )

                encryptVideoFile(
                    inputPath = videoPath,
                    keyHeader = keyHeader
                )

            } else {
                videoPath
            }


        /* ---------- PHASE 5: METADATA ---------- */

        val durationMs = FFmpegUtils.getDurationMs(compressedPath)
        val codec = detectVideoCodec(finalVideoPath)

        val playlistContent =
            if (isSegmented && playlistPath != null) {
                fileOperationsProvider.readFileBytes(playlistPath).decodeToString()
            } else {
                null
            }

        val metadata =
            VideoMetadata(
                mimeType =
                    if (isSegmented) "application/vnd.apple.mpegurl" else "video/mp4",
                isSegmented = isSegmented,
                fileSize = fileOperationsProvider.getFileSize(finalVideoPath),
                duration = durationMs.toFloat(),
                codec = codec,
                hlsPlaylist = playlistContent,
                key = payload.key // point directly to the payload
            )

        val metadataJson = OdinSystemSerializer.serialize(metadata)
        val shouldEmbed =
            metadataJson.length < HomebaseProtocol.MaxPayloadDescriptorBytes

        /* ---------- PHASE 6: PAYLOADS ---------- */

        val videoPayload =
            PayloadFile(
                key = payload.key,
                filePath = finalVideoPath,
                contentType = if (isSegmented) "video/mp2t" else "video/mp4",
                descriptorContent =
                    if (shouldEmbed) metadataJson
                    else OdinSystemSerializer.serialize(
                        metadata.copy(
                            isDescriptorContentComplete = false,
                            hlsPlaylist = null,  // too large — full playlist goes in separate payload
                            key = descriptorContentPayloadKey  // tells player where to fetch full metadata
                        )
                    ),
                isPreEncrypted = true,
                previewThumbnail = tinyThumb,
                iv = keyHeader.iv
            )

        val payloads =
            if (shouldEmbed) {
                listOf(videoPayload)
            } else {
                listOf(
                    videoPayload,
                    PayloadFile(
                        key = descriptorContentPayloadKey,
                        filePath =
                            fileOperationsProvider.writeBytesToTempFile(
                                metadataJson.toByteArray(),
                                "payload",
                                ".metadata"
                            ),
                        contentType = "application/json",
                        isPreEncrypted = false
                    )
                )
            }

        /* ---------- RESULT ---------- */

        onProgress?.invoke(
            VideoPayloadProgressPhase(
                payload.key,
                VideoProcessingPhase.COMPLETE,
                0f
            )
        )

        return VideoProcessResult(
            payloads = payloads,
            thumbnails = thumbnails,
            videoMetadata = metadata
        )
    }

    private suspend fun encryptVideoFile(
        inputPath: String,
        keyHeader: KeyHeader
    ): String {
        val bytes = fileOperationsProvider.readFileBytes(inputPath)
        val encrypted = keyHeader.encryptDataAes(bytes)

        return fileOperationsProvider.writeBytesToTempFile(
            encrypted,
            "video-encrypted",
            ".bin"
        )
    }

    private suspend fun detectVideoCodec(filePath: String): String {
        // TODO: ffprobe later

        return "h264"
    }
}


