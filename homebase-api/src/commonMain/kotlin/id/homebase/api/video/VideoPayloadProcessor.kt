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

    suspend fun process(
        payload: PayloadFile,
        keyHeader: KeyHeader,
        onProgress: ((PayloadProgressPhase) -> Unit)?,
        auxiliaryPayloadKey: String

    ): VideoProcessResult {

        /* ---------- PHASE 1: THUMBNAIL ---------- */

        onProgress?.invoke(
            PayloadProgressPhase(payload.key, "thumbnail", 0f)
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
                thumbnails = generatedThumbnails
            } finally {
                runCatching {
                    fileOperationsProvider.deleteTempFile(thumbnailPath)
                }
            }
        }

        /* ---------- PHASE 2: SEGMENT + ENCRYPT ---------- */

        val (playlistPath, segmentPath) =
            FFmpegUtils.segmentAndEncryptVideo(
                inputPath = payload.filePath,
                keyHeader = keyHeader,
                onProgress = { pct ->
                    onProgress?.invoke(
                        PayloadProgressPhase(payload.key, "segmenting", pct)
                    )
                }
            ) ?: error("segmentAndEncryptVideo returned null")

        /* ---------- PHASE 3: METADATA + DESCRIPTOR ---------- */

        val segmentSize =
            fileOperationsProvider.getFileSize(segmentPath)

        val durationMs = FFmpegUtils.getDurationMs(payload.filePath)

        val metadata =
            VideoMetadata(
                mimeType = "application/vnd.apple.mpegurl",
                isSegmented = true,
                fileSize = segmentSize,
                durationMs = durationMs,
                key = payload.key
            )

        val metadataJson = OdinSystemSerializer.serialize(metadata)
        val shouldEmbed = metadataJson.length < HomebaseProtocol.MaxPayloadDescriptorBytes

        /* ---------- PHASE 4: BUILD PAYLOADS ---------- */

        val videoPayload =
            PayloadFile(
                key = payload.key,
                filePath = segmentPath,
                contentType = "video/mp2t",
                descriptorContent =
                    if (shouldEmbed) {
                        metadataJson
                    } else {
                        OdinSystemSerializer.serialize(metadata.copy(isDescriptorContentComplete = false))
                    },
                isPreEncrypted = true,
                iv = keyHeader.iv
            )

        val payloads =
            if (shouldEmbed) {
                listOf(videoPayload)
            } else {
                listOf(
                    videoPayload,
                    PayloadFile(
                        key = auxiliaryPayloadKey,
                        filePath = fileOperationsProvider.writeBytesToTempFile(
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

        return VideoProcessResult(
            payloads = payloads,
            thumbnails = thumbnails,
            tinyThumb = tinyThumb,
            playlistPath = playlistPath,
            videoMetadata = metadata
        )
    }
}


