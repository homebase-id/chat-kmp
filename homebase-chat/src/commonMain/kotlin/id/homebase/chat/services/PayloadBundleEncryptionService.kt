package id.homebase.chat.services

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.writeBytesToTempFile
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.video.FFmpegUtils
import id.homebase.api.video.VideoSegmentException

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

                if (payload.contentType.startsWith("video/")) {

                    val video = encryptVideo(payload.filePath, keyHeader)

                    // TODO: what to do w/ this? video.segmentPath

                    payload.copy(
                        filePath = video.playlistPath,
                        iv = video.iv,
                        isPreEncrypted = true
                    )

                } else {
                    val encrypted = encryptFile(payload.filePath, keyHeader)
                    payload.copy(
                        filePath = encrypted.filePath,
                        iv = encrypted.iv,
                        isPreEncrypted = true
                    )
                }
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
    ): EncryptedVideoResult {

        val (playlistPath, segmentPath) =
            try {
                FFmpegUtils.segmentAndEncryptVideo(
                    inputPath = inputFile,
                    keyHeader = keyHeader
                ) ?: throw IllegalStateException(
                    "FFmpeg returned null for segmentAndEncryptVideo: $inputFile"
                )
            } catch (e: VideoSegmentException) {
                // ✅ this is where you handle FFmpeg failures

                // log
                // map to UI error
                // retry if appropriate
                // attach diagnostics

                throw e // IMPORTANT: rethrow unless you intentionally recover
            }

        // IMPORTANT:
        // - IV must be the same IV used by FFmpeg (from KeyHeader)
        // - This IV is reused for thumbnails later
        return EncryptedVideoResult(
            playlistPath = playlistPath,
            segmentPath = segmentPath,
            iv = keyHeader.iv
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
