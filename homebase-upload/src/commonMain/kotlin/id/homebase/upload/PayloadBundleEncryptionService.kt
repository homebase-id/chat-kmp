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
import id.homebase.api.client.drives.files.PayloadHash
import id.homebase.api.crypto.encryptFileStreaming
import id.homebase.api.crypto.encryptWithPayloadHash
import id.homebase.api.crypto.plaintextContentHash
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.SourceUnavailableException
import id.homebase.api.video.VideoPayloadProgressPhase
import id.homebase.api.video.VideoPayloadProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

private const val TAG = "PayloadBundleEncryption"

class PayloadBundleEncryptionService(
    private val fileOps: FileOperationsProvider,
    private val videoProcessor: VideoPayloadProcessor,
    private val eventBus: EventBus,
) : PayloadBundleEncryptor {

    override suspend fun stageBundle(bundle: PayloadBundle?): PayloadBundle {
        if (bundle == null) return EMPTY_BUNDLE
        // Video processing (compress, segment) only exists encrypted, and a raw phone video must never go out as is.
        require(bundle.payloads.none { it.contentType.startsWith("video/") && !it.skipVideoProcessing }) {
            "an unencrypted upload can't carry a video yet (#2481)"
        }
        requireSources(bundle)
        val staged = mutableListOf<String>()
        try {
            // Always a copy: the outbox deletes a row's payload files once sent, and the source may be the person's own.
            val payloads = bundle.payloads.map { payload ->
                val stripped = withMetadataStripped(payload, fileOps, staged = true)
                val copy = if (stripped.filePath != payload.filePath) stripped else {
                    val path = fileOps.createOutboxStagingPath("public_", ".bin")
                    fileOps.writeStream(path, fileOps.readFileAsFlow(payload.filePath, STAGE_CHUNK))
                    payload.copy(filePath = path)
                }
                copy.also { staged += it.filePath }
            }
            return bundle.copy(payloads = payloads)
        } catch (e: Throwable) {
            staged.forEach { runCatching { fileOps.deleteTempFile(it) } }
            throw e
        }
    }

    override suspend fun discard(bundle: PayloadBundle) = withContext(NonCancellable) {
        bundle.payloads.forEach { runCatching { fileOps.deleteTempFile(it.filePath) } }
        cleanupHlsScratch(bundle.payloads)
    }

    override suspend fun encryptBundle(
        uniqueId: Uuid,
        bundle: PayloadBundle?,
        aesKey: SecureByteArray,
        scope: CoroutineScope,
        hashes: ContentHashes?,
    ): PayloadBundle {

        if (bundle == null) return EMPTY_BUNDLE

        // Fail soft: these raw sources are disposable pre-encryption temps. If one was
        // swept by the CacheSweeper, OS-evicted, or had its content://`/`ph:// grant
        // revoked before send, reading it must not crash the send — and because this
        // runs BEFORE the outbox enqueue, throwing here guarantees no doomed row.
        // Probe the non-video sources up front so the common case throws before any
        // encryption happens (nothing partial to clean up). Video sources are
        // resolved+streamed inside VideoPayloadProcessor (and can be web blob URLs that
        // aren't probe-able as files), so they're left to their own failure surface;
        // encryptFile re-checks per file below to close the swept-mid-send race.
        requireSources(bundle)

        // Note: the incoming payloads from the bundle will
        // be unencrypted and ordered
        val newPayloads = mutableListOf<PayloadFile>()
        val newThumbnails = mutableListOf<ThumbnailFile>()

        try {
            var index = 0;
            for (payload in bundle.payloads) {

                // Its caller encrypted it with this key and its own IV, and vouches for its bytes.
                val preEncryptedIv = payload.iv?.takeIf { payload.isPreEncrypted }
                // payloads get their own IV
                val keyHeader = KeyHeader(
                    aesKey = aesKey,
                    iv = preEncryptedIv ?: ByteArrayUtil.getRndByteArray(16)
                )

                if (preEncryptedIv != null) {
                    newPayloads += payload
                    newThumbnails += bundle.thumbnails.filter { it.key == payload.key }.map { it.copy(thumbnailBytes = encryptBytes(it.thumbnailBytes, keyHeader)) }
                } else if (payload.contentType.startsWith("video/") && !payload.skipVideoProcessing) {
                    require(hashes?.expected?.containsKey(payload.key) != true) { "a transcoded video can't be checked against its source's hash" }

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
                    val sourcePath = withMetadataStripped(payload, fileOps, staged = false).filePath
                    val encrypted = encryptFile(sourcePath, keyHeader, payload.key, hashes)
                    if (sourcePath != payload.filePath) {
                        runCatching { fileOps.deleteTempFile(sourcePath) }
                    }
                    newPayloads += payload.copy(
                        filePath = encrypted.path,
                        iv = keyHeader.iv,
                        isPreEncrypted = true,
                        hash = encrypted.hash,
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
            discard(bundle.copy(payloads = newPayloads))
            throw e
        }

        return bundle.copy(
            payloads = newPayloads, thumbnails = newThumbnails
        )
    }

    private class Encrypted(val path: String, val hash: PayloadHash?)

    private suspend fun encryptFile(inputFile: String, keyHeader: KeyHeader, payloadKey: String, hashes: ContentHashes?): Encrypted {
        // Defense-in-depth for the swept-mid-send race: the up-front probe in
        // encryptBundle already rejected a missing source, but it could be swept
        // between that probe and this read. A typed throw keeps the send fail-soft.
        if (!fileOps.sourceExists(inputFile)) throw SourceUnavailableException(inputFile)

        // The durable outbox staging dir, not upload-temp: this file rides an outbox row until
        // the send completes and must survive sweeps; the outbox reaps it on success or drop.
        val path = fileOps.createOutboxStagingPath(prefix = "enc", suffix = ".encrypted")
        if (hashes == null) {
            fileOps.encryptFileStreaming(inputFile, path, keyHeader)
            return Encrypted(path, null)
        }
        val hash = fileOps.encryptWithPayloadHash(inputFile, path, keyHeader, hashes.algorithm) { chunk, offset, length ->
            hashes.onPlaintext(payloadKey, chunk, offset, length)
        }
        val expected = hashes.expected[payloadKey]
        if (expected != null && !expected.contentEquals(hash.plaintextContentHash(keyHeader))) {
            runCatching { fileOps.deleteTempFile(path) }
            throw ContentChangedException(payloadKey)
        }
        return Encrypted(path, hash)
    }

    private suspend fun encryptBytes(
        plainBytes: ByteArray,
        keyHeader: KeyHeader
    ): ByteArray {
        val encrypted = keyHeader.encryptDataAes(data = plainBytes)
        return encrypted
    }

    // Video sources are resolved and streamed by VideoPayloadProcessor (and may be web blob URLs), so they aren't probed.
    private suspend fun requireSources(bundle: PayloadBundle) {
        for (payload in bundle.payloads) {
            if (!payload.contentType.startsWith("video/") && !fileOps.sourceExists(payload.filePath)) {
                throw SourceUnavailableException(payload.filePath)
            }
        }
    }

    private companion object {
        const val STAGE_CHUNK = 256 * 1024
    }
}
