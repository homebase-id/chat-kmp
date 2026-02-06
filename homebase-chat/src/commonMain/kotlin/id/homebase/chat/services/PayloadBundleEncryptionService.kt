package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.video.PayloadProgressPhase
import id.homebase.api.video.VideoPayloadProcessor

class PayloadBundleEncryptionService(
    private val fileOps: FileOperationsProvider,
    private val videoProcessor: VideoPayloadProcessor
) {

    val TAG: String = "PayloadBundleEncryptionService"
    suspend fun encryptBundle(
        bundle: PayloadBundle?,
        keyHeader: KeyHeader,
        onProgress: ((PayloadProgressPhase) -> Unit)? = null
    ): PayloadBundle {

        if (bundle == null) {
            return PayloadBundle(
                payloads = emptyList(),
                thumbnails = emptyList(),
                previewThumbs = emptyList()
            )
        }

        val newPayloads = mutableListOf<PayloadFile>()
        val newThumbnails = mutableListOf<ThumbnailFile>()

        for (payload in bundle.payloads) {

            Logger.i(TAG) {
                "IN payload key=${payload.key} " +
                        "type=${payload.contentType} " +
                        "path=${payload.filePath}"
            }
            if (payload.contentType.startsWith("video/")) {

                val result =
                    videoProcessor.process(
                        payload = payload,
                        keyHeader = keyHeader,
                        onProgress = onProgress,
                        auxiliaryPayloadKey = ChatProtocol.DEFAULT_PAYLOAD_DESCRIPTOR_KEY
                    )

                newPayloads += result.payloads
                newThumbnails += result.thumbnails

                result.payloads.forEach {
                    Logger.i(TAG) {
                        "OUT video payload key=${it.key} " +
                                "type=${it.contentType} " +
                                "path=${it.filePath}"
                    }
                }


            } else {
                val encrypted = encryptFile(payload.filePath, keyHeader)

                Logger.i(TAG) {
                    "OUT file payload key=${payload.key} " +
                            "path=${encrypted.filePath}"
                }

                newPayloads += payload.copy(
                    filePath = encrypted.filePath,
                    iv = encrypted.iv,
                    isPreEncrypted = true
                )
            }
        }

        return bundle.copy(
            payloads = newPayloads,
            thumbnails = newThumbnails
        )
    }

    private suspend fun encryptFile(
        inputFile: String,
        keyHeader: KeyHeader
    ): EncryptedFileResult {
        val plainBytes = fileOps.readFileBytes(inputFile)
        val iv = ByteArrayUtil.getRndByteArray(16)

        val encrypted =
            keyHeader.encryptDataAes(
                data = plainBytes,
                customIv = iv
            )

        val path =
            fileOps.writeBytesToTempFile(
                bytes = encrypted,
                prefix = "enc",
                suffix = ".encrypted"
            )

        return EncryptedFileResult(
            filePath = path,
            iv = iv
        )
    }
}
