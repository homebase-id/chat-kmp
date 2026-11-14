package id.homebase.upload

import id.homebase.api.HomebaseProtocol
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.cleanupHlsScratch
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.crypto.encryptFileStreaming
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.SourceUnavailableException
import id.homebase.api.video.VideoPayloadProgressPhase
import id.homebase.api.video.VideoPayloadProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

private const val TAG = "PayloadBundleEncryption"

class PayloadBundleEncryptionService(
    private val fileOps: FileOperationsProvider,
    private val videoProcessor: VideoPayloadProcessor,
    private val eventBus: EventBus,
) : PayloadBundleEncryptor {

    override suspend fun encryptBundle(
        uniqueId: Uuid,
        bundle: PayloadBundle?,
        aesKey: SecureByteArray,
        scope: CoroutineScope
    ): PayloadBundle {

        if (bundle == null) {
            return PayloadBundle(
                payloads = emptyList(), thumbnails = emptyList(), previewThumbs = emptyList()
            )
        }

        // Fail soft: these raw sources are disposable pre-encryption temps. If one was
        // swept by the CacheSweeper, OS-evicted, or had its content://`/`ph:// grant
        // revoked before send, reading it must not crash the send — and because this
        // runs BEFORE the outbox enqueue, throwing here guarantees no doomed row.
        // Probe the non-video sources up front so the common case throws before any
        // encryption happens (nothing partial to clean up). Video sources are
        // resolved+streamed inside VideoPayloadProcessor (and can be web blob URLs that
        // aren't probe-able as files), so they're left to their own failure surface;
        // encryptFile re-checks per file below to close the swept-mid-send race.
        for (payload in bundle.payloads) {
            if (!payload.contentType.startsWith("video/") && !fileOps.sourceExists(payload.filePath)) {
                throw SourceUnavailableException(payload.filePath)
            }
        }

        // Note: the incoming payloads from the bundle will
        // be unencrypted and ordered
        val newPayloads = mutableListOf<PayloadFile>()
        val newThumbnails = mutableListOf<ThumbnailFile>()

        try {
            var index = 0;
            for (payload in bundle.payloads) {

                // payloads get their own IV
                val keyHeader = KeyHeader(
                    aesKey = aesKey,
                    iv = ByteArrayUtil.getRndByteArray(16)
                )

                if (payload.contentType.startsWith("video/") && !payload.skipVideoProcessing) {

                    val progress: (VideoPayloadProgressPhase) -> Unit = { phase ->
                        scope.launch {
                            eventBus.emit(
                                BackendEvent.PayloadBundlingEvent.Video.PhaseProgress(
                                    uniqueId = uniqueId,
                                    payloadKey = phase.payloadKey,
                                    phase = phase.phase,
                                    progress = phase.progress
                                )
                            )
                        }
                    }

                    val result = videoProcessor.process(
                        payload = payload,
                        keyHeader = keyHeader,
                        onProgress = progress,
                        descriptorContentPayloadKey = HomebaseProtocol.payloadDescriptorKey(index),
                        playlistPayloadKey = HomebaseProtocol.playlistPayloadKey(index),
                        trimStartMs = payload.trimStartMs,
                        trimEndMs = payload.trimEndMs,
                        inputBlobUrl = payload.inputBlobUrl,
                        videoQuality = payload.videoQuality,
                    )

                    newPayloads += result.payloads
                    newThumbnails += result.thumbnails
                } else {
                    val sourcePath = if (payload.stripMetadata) withoutImageMetadata(payload.filePath, payload.contentType, fileOps) else payload.filePath
                    val encryptedFile = encryptFile(sourcePath, keyHeader)
                    if (sourcePath != payload.filePath) {
                        runCatching { fileOps.deleteTempFile(sourcePath) }
                    }
                    newPayloads += payload.copy(
                        filePath = encryptedFile,
                        iv = keyHeader.iv,
                        isPreEncrypted = true
                    )

                    val encryptedThumbnails =
                        bundle.thumbnails.filter { it.key == payload.key }.map { thumb ->
                            val encryptedBytes = encryptBytes(thumb.thumbnailBytes, keyHeader)
                            thumb.copy(thumbnailBytes = encryptedBytes)
                        }
                    newThumbnails += encryptedThumbnails
                }

                index++;
            }
        } catch (e: Throwable) {
            // Any failure mid-bundle — a source swept between the up-front probe and its read,
            // or a video whose transcode failed (VideoCompressionFailedException). Reap the
            // encrypted temps produced so far so the failed send leaves no orphaned
            // enc*/video temps, then rethrow to fail soft.
            newPayloads.forEach { runCatching { fileOps.deleteTempFile(it.filePath) } }
            // The per-file delete above reaps a staged HLS index.ts but leaves its
            // hls_<uuid>/ parent (and sibling playlist). In the durable staging dir that
            // would linger until the idle reap instead of the next startup sweep (#842).
            runCatching { cleanupHlsScratch(newPayloads) }
            throw e
        }

        return bundle.copy(
            payloads = newPayloads, thumbnails = newThumbnails
        )
    }

    private suspend fun encryptFile(
        inputFile: String, keyHeader: KeyHeader
    ): String {
        // Defense-in-depth for the swept-mid-send race: the up-front probe in
        // encryptBundle already rejected a missing source, but it could be swept
        // between that probe and this read. A typed throw keeps the send fail-soft.
        if (!fileOps.sourceExists(inputFile)) throw SourceUnavailableException(inputFile)

        // The durable outbox staging dir, not upload-temp: this file rides an outbox row until
        // the send completes and must survive sweeps; the outbox reaps it on success or drop.
        val path = fileOps.createOutboxStagingPath(prefix = "enc", suffix = ".encrypted")
        fileOps.encryptFileStreaming(inputFile, path, keyHeader)

        return path
    }

    private suspend fun encryptBytes(
        plainBytes: ByteArray,
        keyHeader: KeyHeader
    ): ByteArray {
        val encrypted = keyHeader.encryptDataAes(data = plainBytes)
        return encrypted
    }
}
