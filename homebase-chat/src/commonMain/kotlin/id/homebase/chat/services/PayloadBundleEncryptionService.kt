package id.homebase.chat.services

import co.touchlab.kermit.Logger
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
) {

    val TAG: String = "PayloadBundleEncryptionService"
    suspend fun encryptBundle(
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
                aesKey = aesKey, iv = ByteArrayUtil.getRndByteArray(16)
            )

            Logger.i(TAG) {
                "IN payload key=${payload.key} " + "type=${payload.contentType} " + "path=${payload.filePath}"
            }

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

            if (payload.contentType.startsWith("video/")) {

                val result = videoProcessor.process(
                    payload = payload,
                    keyHeader = keyHeader,
                    onProgress = progress,
                    descriptorContentPayloadKey = "${ChatProtocol.DEFAULT_PAYLOAD_DESCRIPTOR_KEY}$index"
                )

                newPayloads += result.payloads
                newThumbnails += result.thumbnails

                result.payloads.forEach {
                    Logger.i(TAG) {
                        "OUT video payload key=${it.key} " + "type=${it.contentType} " + "path=${it.filePath}"
                    }
                }

            } else {
                val encrypted = encryptFile(payload.filePath, keyHeader)

                Logger.i(TAG) {
                    "OUT file payload key=${payload.key} " + "path=${encrypted.filePath}"
                }

                newPayloads += payload.copy(
                    filePath = encrypted.filePath, iv = encrypted.iv, isPreEncrypted = true
                )
            }

            index++;
        }

        return bundle.copy(
            payloads = newPayloads, thumbnails = newThumbnails
        )
    }

    private suspend fun encryptFile(
        inputFile: String, keyHeader: KeyHeader
    ): EncryptedFileResult {
        val plainBytes = fileOps.readFileBytes(inputFile)
        val iv = ByteArrayUtil.getRndByteArray(16)

        val encrypted = encryptBytes(plainBytes, keyHeader)

        val path = fileOps.writeBytesToTempFile(
            bytes = encrypted, prefix = "enc", suffix = ".encrypted"
        )

        return EncryptedFileResult(
            filePath = path, iv = iv
        )
    }

    private suspend fun encryptBytes(
        plainBytes: ByteArray, keyHeader: KeyHeader
    ): ByteArray {
        val encrypted = keyHeader.encryptDataAes(data = plainBytes)
        return encrypted
    }
}
