package id.homebase.chat.services

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.video.VideoPayloadProgressPhase
import id.homebase.api.video.VideoPayloadProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class PayloadBundleEncryptionService(
    private val fileOps: FileOperationsProvider,
    private val videoProcessor: VideoPayloadProcessor,
    private val eventBus: EventBus
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

        // Note: the incoming payloads from the bundle will
        // be unencrypted and ordered
        val newPayloads = mutableListOf<PayloadFile>()
        val newThumbnails = mutableListOf<ThumbnailFile>()

        var index = 0;
        for (payload in bundle.payloads) {

            // payloads get their own IV
            val keyHeader = KeyHeader(
                aesKey = aesKey,
                iv = ByteArrayUtil.getRndByteArray(16)
            )

            if (payload.contentType.startsWith("video/")) {

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
                    descriptorContentPayloadKey = "${ChatProtocol.DEFAULT_PAYLOAD_DESCRIPTOR_KEY}$index",
                    trimStartMs = payload.trimStartMs,
                    trimEndMs = payload.trimEndMs,
                )

                newPayloads += result.payloads
                newThumbnails += result.thumbnails
            } else {
                val encryptedFile = encryptFile(payload.filePath, keyHeader)
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

        return bundle.copy(
            payloads = newPayloads, thumbnails = newThumbnails
        )
    }

    private suspend fun encryptFile(
        inputFile: String, keyHeader: KeyHeader
    ): String {
        val plainBytes = fileOps.readFileBytes(inputFile)

        val encrypted = encryptBytes(plainBytes, keyHeader)

        val path = fileOps.writeBytesToTempFile(
            bytes = encrypted, prefix = "enc", suffix = ".encrypted"
        )

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
