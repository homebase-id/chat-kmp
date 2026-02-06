package id.homebase.chat.services

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.writeBytesToTempFile
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.video.PayloadProgressPhase
import id.homebase.api.video.VideoPayloadProcessor

class PayloadBundleEncryptionService(
    private val fileOps: FileOperationsProvider,
    private val videoProcessor: VideoPayloadProcessor
) {

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

            if (payload.contentType.startsWith("video/")) {

                val result =
                    videoProcessor.process(
                        payload = payload,
                        keyHeader = keyHeader,
                        onProgress = onProgress
                    )

                newPayloads += result.payloads
                newThumbnails += result.thumbnails

            } else {
                // ✅ EXACTLY your existing logic — nothing invented
                val encrypted = encryptFile(payload.filePath, keyHeader)
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
            writeBytesToTempFile(
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
