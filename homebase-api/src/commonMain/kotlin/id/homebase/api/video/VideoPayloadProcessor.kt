package id.homebase.api.video

import co.touchlab.kermit.Logger
import id.homebase.api.HomebaseProtocol
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.files.WholePercentProgressGate
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.crypto.AesCbc
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.withResolvedFile
import id.homebase.api.image.createThumbnails
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.utils.io.core.toByteArray
import okio.Path.Companion.toPath
import kotlin.time.Duration
import kotlin.time.measureTimedValue


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
        inputBlobUrl: String? = null,
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
                inputBlobUrl = inputBlobUrl,
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
        // Web only: a blob: URL for the original. When present it's the ffmpeg/decoder INPUT read
        // (poster + compress), so the original is read in JS — never copied into Kotlin or base64'd.
        // null on native → falls back to the okio path. Read-only here; revoked by writeFileFromUrl.
        inputBlobUrl: String?,
    ): VideoProcessResult {
        // The okio path stays the source of truth for size, the compress-skip fallback, and the
        // non-HLS encrypt of an uncompressed clip; inputBlobUrl only short-circuits the INPUT reads.
        val ffmpegInputPath = inputBlobUrl ?: payload.filePath

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
        val posterBytes = VideoThumbnailService.extractPosterFrame(ffmpegInputPath)
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

        val inputSize = fileOperationsProvider.getFileSize(payload.filePath)
        // Collapse the per-tick ffmpeg progress to one event per whole percent (fractions are
        // 0f..1f, so key on it*100). Primed at 0 so the first tick doesn't duplicate the
        // phase-start 0f emitted above. Without this every raw tick became a suspending
        // PhaseProgress emit — the same EventBus flood the upload path had. See
        // WholePercentProgressGate.
        val compressGate = WholePercentProgressGate().also { it.admit(0f) }
        val (compressedPath, compressElapsed) =
            measureTimedValue {
                compressor.compress(
                    inputPath = ffmpegInputPath,
                    trimStartMs = trimStartMs,
                    trimEndMs = trimEndMs,
                    quality = videoQuality,
                    onProgress = {
                        if (compressGate.admit(it * 100f) != null) {
                            onProgress?.invoke(
                                VideoPayloadProgressPhase(
                                    payload.key,
                                    VideoProcessingPhase.COMPRESSING,
                                    it
                                )
                            )
                        }
                    },
                )
            }

        // Everything below consumes the compressed scratch — segmentation, encryption,
        // and the plaintext metadata probe — so reap it on EVERY exit, including a
        // segmentation/encryption failure that throws before the success-path read.
        // Otherwise a repeatedly-failing send (the exact case users retry into) piles up
        // full-size compressed_*.mp4 files in the cache until the next startup sweep.
        // Skipped when compressVideo fell back to the input path (compressedPath IS
        // payload.filePath, owned by the caller).
        return try {
            finishProcessing(
                payload = payload,
                keyHeader = keyHeader,
                onProgress = onProgress,
                descriptorContentPayloadKey = descriptorContentPayloadKey,
                compressedPath = compressedPath,
                compressElapsed = compressElapsed,
                inputSize = inputSize,
                videoQuality = videoQuality,
                trimStartMs = trimStartMs,
                trimEndMs = trimEndMs,
                tinyThumb = tinyThumb,
                thumbnails = thumbnails,
            )
        } finally {
            if (compressedPath != payload.filePath) {
                fileOperationsProvider.deleteTempFile(compressedPath)
            }
        }
    }

    private suspend fun finishProcessing(
        payload: PayloadFile,
        keyHeader: KeyHeader,
        onProgress: ((VideoPayloadProgressPhase) -> Unit)?,
        descriptorContentPayloadKey: String,
        compressedPath: String,
        compressElapsed: Duration,
        inputSize: Long,
        videoQuality: VideoQuality,
        trimStartMs: Long?,
        trimEndMs: Long?,
        tinyThumb: EmbeddedThumb?,
        thumbnails: List<ThumbnailFile>,
    ): VideoProcessResult {

        /* ---------- PHASE 3: SIZE CHECK → HLS DECISION ---------- */

        val compressedSize = fileOperationsProvider.getFileSize(compressedPath)
        val useHls = compressedSize >= FIVE_MB

        Logger.i(tag = "VideoProcessing") {
            "compression finished in $compressElapsed: input=${inputSize}B → compressed=${compressedSize}B " +
                "quality=$videoQuality trim=$trimStartMs..$trimEndMs skipped=${compressedPath == payload.filePath}"
        }
        Logger.i(tag = "VideoProcessing") {
            "HLS decision: compressed=${compressedSize}B threshold=${FIVE_MB}B → " +
                if (useHls) "SEGMENT (hls)" else "direct mp4"
        }

        /* ---------- PHASE 4: SEGMENT (HLS ONLY) ---------- */

        val (playlistPath, videoPath, isSegmented) =
            if (useHls) {
                // Same whole-percent throttle as compression (see compressGate). No priming —
                // there is no separate SEGMENTING phase-start emit, so the first tick should land.
                val segmentGate = WholePercentProgressGate()
                val (segmented, segmentElapsed) =
                    measureTimedValue {
                        compressor.segmentAndEncrypt(
                            inputPath = compressedPath,
                            keyHeader = keyHeader,
                            onProgress = { pct ->
                                if (segmentGate.admit(pct * 100f) != null) {
                                    onProgress?.invoke(
                                        VideoPayloadProgressPhase(
                                            payload.key,
                                            VideoProcessingPhase.SEGMENTING,
                                            pct
                                        )
                                    )
                                }
                            }
                        ) ?: run {
                            Logger.e(tag = "VideoProcessing") {
                                "segmentAndEncrypt returned null (HLS path) — " +
                                    "compressed=${compressedSize}B quality=$videoQuality " +
                                    "path=$compressedPath"
                            }
                            error("segmentAndEncryptVideo failed")
                        }
                    }

                Logger.i(tag = "VideoProcessing") {
                    "segmentation finished in $segmentElapsed: playlist=${segmented.playlistPath} " +
                        "segments=${fileOperationsProvider.getFileSize(segmented.segmentsPath)}B"
                }

                // Promote the finished hls_<uuid>/ dir out of cacheDir scratch into the
                // durable outbox staging dir (#842): the segment file rides an outbox row
                // (possibly for days) and cacheDir scratch is swept on every startup.
                // FFmpeg keeps writing into cacheDir (failure partials stay owned by
                // FfmpegOutputCleanup / the startup sweep); this promote at the
                // segmentation-success boundary IS the durability boundary. Key material
                // (enc.key/keyinfo.txt) is already deleted by segmentAndEncrypt. The
                // playlist references segments by bare filename, so moving the dir
                // wholesale keeps it valid; cleanupHlsScratch keys off the hls_ dir-name
                // prefix, which the promote preserves.
                val scratchHlsDir = segmented.segmentsPath.toPath().parent
                    ?: error("HLS segments path has no parent: ${segmented.segmentsPath}")
                // Every FFmpegUtils actual writes into its own hls_<uuid>/ dir; promoting
                // anything else wholesale (e.g. a shared scratch root) would drag sibling
                // files along — fail loudly instead.
                check(scratchHlsDir.name.startsWith("hls_")) {
                    "expected an hls_<uuid> segment dir, got $scratchHlsDir"
                }
                val stagedHlsDir =
                    fileOperationsProvider.promoteToOutboxStaging(scratchHlsDir.toString()).toPath()

                Triple(
                    (stagedHlsDir / segmented.playlistPath.toPath().name).toString(),
                    (stagedHlsDir / segmented.segmentsPath.toPath().name).toString(),
                    true,
                )
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
        // Probe the compressed (pre-encryption) output for real codec info so the
        // descriptor carries truthful metadata for the inline debug overlay. The
        // encrypted/segmented final file can't be probed, so this runs on the
        // plaintext compressedPath before it's reaped below. Routed through the
        // VideoProber seam (not FFmpegUtils directly) to honour @LowLevelFfmpegApi.
        val outProbe = probe.probeVideo(compressedPath)
        val videoBitrateBps =
            if (durationMs > 0L) compressedSize * 8L * 1000L / durationMs else 0L
        val codec = outProbe?.codec ?: detectVideoCodec(finalVideoPath)

        // (The compressed_*.mp4 scratch is reaped by the caller's finally — its bytes
        // have already been re-encoded into the HLS segment or encrypted payload, and
        // its metadata has been read above.)

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
                key = payload.key, // point directly to the payload
                widthPx = outProbe?.widthPx ?: 0,
                heightPx = outProbe?.heightPx ?: 0,
                bitDepth = outProbe?.bitDepth ?: 0,
                isHdr = outProbe?.isHdr ?: false,
                videoBitrateBps = videoBitrateBps,
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
                            // Encrypted, ready-to-transmit and referenced by the outbox row —
                            // belongs in the durable staging bucket, NOT the disposable
                            // upload-temp/ that gets swept every startup (#842).
                            fileOperationsProvider.writeBytesToOutboxTempFile(
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

        Logger.i(tag = "VideoProcessing") {
            "video payload ready: segmented=$isSegmented duration=${durationMs}ms"
        }

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
        // Encrypted output goes straight into the durable outbox staging dir (#842) —
        // writing it to cacheDir root left it untracked, so the startup sweep / OS
        // cache reclaim deleted it out from under a pending outbox row (ENOENT retries).
        val outputPath =
            fileOperationsProvider.createOutboxStagingPath("video-encrypted-", ".bin")
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


