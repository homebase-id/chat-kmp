package id.homebase.chat.services

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.writeBytesToTempFile
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.file.FileOperationsProvider

class PayloadBundleEncryptionService(
    private val fileOps: FileOperationsProvider
) {

    suspend fun encryptBundle(
        bundle: PayloadBundle?,
        keyHeader: KeyHeader
    ): PayloadBundle {

        if (bundle == null) {
            return PayloadBundle(
                payloads = emptyList(),
                thumbnails = emptyList(),
                previewThumbs = emptyList()
            )
        }

        val encryptedPayloads =
            bundle.payloads.map { payload ->

                val encrypted =
                    if (payload.contentType.startsWith("video/")) {
                        encryptVideo(payload.filePath, keyHeader)
                    } else {
                        encryptFile(payload.filePath, keyHeader)
                    }

                payload.copy(
                    filePath = encrypted.filePath,
                    iv = encrypted.iv,
                    isPreEncrypted = true
                )
            }

        val ivByKey =
            encryptedPayloads.associate { payload ->
                payload.key to (payload.iv ?: error("Missing IV for payload ${payload.key}"))
            }

        val encryptedThumbnails =
            bundle.thumbnails.map { thumb ->

                if (thumb.skipEncryption) {
                    thumb
                } else {
                    val iv =
                        ivByKey[thumb.key]
                            ?: error("No payload IV found for thumbnail key=${thumb.key}")

                    val encryptedBytes =
                        encryptBytes(
                            data = thumb.thumbnailBytes,
                            keyHeader = keyHeader,
                            iv = iv
                        )

                    thumb.copy(
                        thumbnailBytes = encryptedBytes,
                        skipEncryption = true
                    )
                }
            }

        return bundle.copy(
            payloads = encryptedPayloads,
            thumbnails = encryptedThumbnails
            // previewThumbs intentionally untouched
        )
    }

    /* ============================
       Private helpers
       ============================ */

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

    private suspend fun encryptVideo(
        inputFile: String,
        keyHeader: KeyHeader
    ): EncryptedFileResult {
        // Delegates to your existing logic
        return encryptFile(
            inputFile = inputFile,
            keyHeader = keyHeader
        )
    }


    private suspend fun encryptBytes(
        data: ByteArray,
        keyHeader: KeyHeader,
        iv: ByteArray
    ): ByteArray =
        keyHeader.encryptDataAes(
            data = data,
            customIv = iv
        )
}
