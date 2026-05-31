package id.homebase.api.video

import id.homebase.api.HomebaseProtocol
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.crypto.AesCbc
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.withResolvedFile
import id.homebase.api.image.createThumbnails
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.utils.io.core.toByteArray
import kotlin.uuid.Uuid


class VideoPayloadProcessor(
    private val fileOperationsProvider: FileOperationsProvider,
    private val compressor: VideoCompressor = VideoCompressionService,
    private val probe: VideoProber = VideoCompressionService,
) {
    private val FIVE_MB = 5L * 1024 * 1024

    suspend fun process(
        payload: PayloadFile,
        keyHeader: KeyHeader,
        onProgress: ((VideoPayloadProgressPhase) -> Unit)?,
        descriptorContentPayloadKey: String,
        trimStartMs: Long? = null,
        trimEndMs: Long? = null,
        videoQuality: VideoQuality = VideoQuality.STANDARD,
    ): VideoProcessResult =
        // Resolve content URIs (Android copies the gallery pick into cacheDir as
        // resolved_*; other platforms no-op) and reap that copy when we're done,
        // so it can't linger at full video size. withResolvedFile is the shared
        // resolve-then-delete scope — see FileOperationsProvider.withResolvedFile.
        fileOperationsProvider.withResolvedFile(payload.filePath) { resolvedPath ->
            processResolved(
                payload =
                    if (resolvedPath != payload.filePath) payload.copy(filePath = resolvedPath)
                    else payload,
                keyHeader = keyHeader,
                onProgress = onProgress,
                descriptorContentPayloadKey = descriptorContentPayloadKey,
                trimStartMs = trimStartMs,
                trimEndMs = trimEndMs,
                videoQuality = videoQuality,
            )
        }

    private suspend fun processResolved(
        payload: PayloadFile,
        keyHeader: KeyHeader,
        onProgress: ((VideoPayloadProgressPhase) -> Unit)?,
        descriptorContentPayloadKey: String,
        trimStartMs: Long?,
        trimEndMs: Long?,
        videoQuality: VideoQuality,
    ): VideoProcessResult {

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

        // Poster frame via the thumbnail seam — returns JPEG bytes directly (tiered
        // native-first decode per platform), so no temp-file round-trip to read+delete.
        val posterBytes = VideoThumbnailService.extractPosterFrame(payload.filePath)
        if (posterBytes != null) {
            val (_, generatedTinyThumb, generatedThumbnails) =
                createThumbnails(posterBytes, payload.key)

            tinyThumb = generatedTinyThumb
            thumbnails = generatedThumbnails.map { thumb ->
                thumb.copy(
                    thumbnailBytes = keyHeader.encryptDataAes(thumb.thumbnailBytes),
                )
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
            compressor.compress(
                inputPath = payload.filePath,
                trimStartMs = trimStartMs,
                trimEndMs = trimEndMs,
                quality = videoQuality,
                onProgress = {
                    onProgress?.invoke(
                        VideoPayloadProgressPhase(
                            payload.key,
                            VideoProcessingPhase.COMPRESSING,
                            it
                        )
                    )
                },
            ) ?: payload.filePath

        /* ---------- PHASE 3: SIZE CHECK → HLS DECISION ---------- */

        val compressedSize = fileOperationsProvider.getFileSize(compressedPath)
        val useHls = compressedSize >= FIVE_MB

        /* ---------- PHASE 4: SEGMENT (HLS ONLY) ---------- */

        val (playlistPath, videoPath, isSegmented) =
            if (useHls) {
                val segmented =
                    compressor.segmentAndEncrypt(
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

                Triple(segmented.playlistPath, segmented.segmentsPath, true)
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

        val durationMs = probe.getDurationMs(compressedPath)
        val codec = detectVideoCodec(finalVideoPath)

        // Reap the FFmpeg compressed_*.mp4 scratch — its bytes have already
        // been re-encoded into either the HLS segment (segmented path) or the
        // encrypted payload (non-segmented path), and metadata has been read.
        // Skip when compressVideo fell back to the input path (no compression
        // happened — compressedPath IS payload.filePath, owned by the caller).
        if (compressedPath != payload.filePath) {
            fileOperationsProvider.deleteTempFile(compressedPath)
        }

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
                                keyHeader.encryptDataAes(metadataJson.toByteArray()),
                                "payload",
                                ".metadata"
                            ),
                        contentType = "application/json",
                        isPreEncrypted = true,
                        iv = keyHeader.iv
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

    internal suspend fun encryptVideoFile(
        inputPath: String,
        keyHeader: KeyHeader
    ): String {
        val outputPath =
            "${fileOperationsProvider.getCacheDirectory()}/video-encrypted-${Uuid.random()}.bin"
        fileOperationsProvider.writeStream(
            path = outputPath,
            data = AesCbc.streamEncryptWithCbc(
                dataStream = fileOperationsProvider.readFileAsFlow(inputPath),
                key = keyHeader.aesKey,
                iv = keyHeader.iv,
            ),
        )
        return outputPath
    }

    private suspend fun detectVideoCodec(filePath: String): String {
        // TODO: ffprobe later

        return "h264"
    }
}


