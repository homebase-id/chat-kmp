package id.homebase.api.client.drives.upload

import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders


/** Pre-computed data for a payload ready to be added to form data. */
private data class ProcessedPayload(
    val key: String,
    val contentType: String,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ProcessedPayload

        if (key != other.key) return false
        if (contentType != other.contentType) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + contentType.hashCode()
        return result
    }
}

/** Pre-computed data for a thumbnail ready to be added to form data. */
private data class ProcessedThumbnail(
    val filename: String,
    val contentType: String,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ProcessedThumbnail

        if (filename != other.filename) return false
        if (contentType != other.contentType) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = filename.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * Builds a MultiPartFormDataContent for uploading files. Ported from TypeScript buildFormData
 * function.
 *
 * @param instructionSet The serializable upload instruction set (with manifest embedded)
 * @param sharedSecretEncryptedDescriptor Optional encrypted file descriptor
 * @param payloads Optional list of payload files to upload
 * @param thumbnails Optional list of thumbnail files to upload
 * @return MultiPartFormDataContent ready for HTTP upload
 */
fun buildUploadFormData(
    instructionSet: UploadInstructionSet,
    sharedSecretEncryptedDescriptor: ByteArray? = null,
    payloads: List<PayloadFile>? = null,
    thumbnails: List<ThumbnailFile>? = null,
    fileOperationsProvider: FileOperationsProvider,
): MultiPartFormDataContent {

    val runtimePayloads =
        payloads?.map { it.toRuntime { path -> fileOperationsProvider.openFileInput(path) } }

    return buildFormDataInternal(
        instructionSet = instructionSet,
        sharedSecretEncryptedDescriptor = sharedSecretEncryptedDescriptor,
        payloads = runtimePayloads,
        thumbnails = thumbnails
    )
}

suspend fun calculateUploadSize(
    payloads: List<PayloadFile>?,
    thumbnails: List<ThumbnailFile>?,
    descriptor: ByteArray?,
    fileOps: FileOperationsProvider
): Long {

    val payloadBytes =
        payloads?.sumOf { fileOps.getFileSize(it.filePath) } ?: 0L

    val thumbnailBytes =
        thumbnails?.sumOf { it.thumbnailBytes.size.toLong() } ?: 0L

    val descriptorBytes =
        descriptor?.size?.toLong() ?: 0L

    return payloadBytes + thumbnailBytes + descriptorBytes
}

/**
 * Builds a MultiPartFormDataContent for updating files.
 *
 * @param instructionSet The serializable update instruction set (with manifest embedded)
 * @param sharedSecretEncryptedDescriptor Optional encrypted file descriptor
 * @param payloads Optional list of payload files to upload
 * @param thumbnails Optional list of thumbnail files to upload
 * @return MultiPartFormDataContent ready for HTTP update
 */
fun buildUpdateFormData(
    instructionSet: FileUpdateInstructionSet,
    sharedSecretEncryptedDescriptor: ByteArray? = null,
    payloads: List<PayloadFile>? = null,
    thumbnails: List<ThumbnailFile>? = null,
    fileOperationsProvider: FileOperationsProvider,
): MultiPartFormDataContent
    {
        val runtimePayloads =
            payloads?.map { it.toRuntime { path -> fileOperationsProvider.openFileInput(path) } }

        return buildFormDataInternal(
            instructionSet = instructionSet,
            sharedSecretEncryptedDescriptor = sharedSecretEncryptedDescriptor,
            payloads = runtimePayloads,
            thumbnails = thumbnails
        )
    }

/**
 * Internal implementation of buildFormData. Pre-computes all encrypted data before building the
 * form to avoid suspend issues.
 */
private inline fun <reified T> buildFormDataInternal(
    instructionSet: T,
    sharedSecretEncryptedDescriptor: ByteArray?,
    payloads: List<RuntimePayloadFile>?,
    thumbnails: List<ThumbnailFile>?
): MultiPartFormDataContent {

    val instructionsJson =
        OdinSystemSerializer.json.encodeToString(instructionSet).encodeToByteArray()

    return MultiPartFormDataContent(
        formData {

            // Instructions
            append(
                "instructions",
                instructionsJson,
                Headers.build {
                    append(HttpHeaders.ContentType, "application/json")
                    append(HttpHeaders.ContentDisposition, "form-data; name=\"instructions\"")
                }
            )

            // Encrypted metadata
            if (sharedSecretEncryptedDescriptor != null) {
                append(
                    "metadata",
                    sharedSecretEncryptedDescriptor,
                    Headers.build {
                        append(HttpHeaders.ContentType, "application/octet-stream")
                        append(HttpHeaders.ContentDisposition, "form-data; name=\"metadata\"")
                    }
                )
            }

            // Payloads (streamed)
            payloads?.forEach { payload ->
                append(
                    "payload",
                    payload.input,
                    Headers.build {
                        append(HttpHeaders.ContentType, payload.contentType)
                        append(
                            HttpHeaders.ContentDisposition,
                            "form-data; name=\"payload\"; filename=\"${payload.key}\""
                        )
                    }
                )

            }

            // Thumbnails (streamed)
            thumbnails?.forEach { thumbnail ->
                append(
                    "thumbnail",
                    thumbnail.thumbnailBytes,
                    Headers.build {
                        append(HttpHeaders.ContentType, thumbnail.contentType)
                        append(
                            HttpHeaders.ContentDisposition,
                            "form-data; name=\"thumbnail\"; filename=\"${thumbnail.key}${thumbnail.pixelWidth}\""
                        )
                    }
                )
            }
        }
    )
}

data class RuntimePayloadFile(
    val key: String,
    val contentType: String,
    val input: InputProvider
)

fun PayloadFile.toRuntime(
    openInput: (String) -> InputProvider
): RuntimePayloadFile =
    RuntimePayloadFile(
        key = key,
        contentType = contentType,
        input = openInput(filePath)
    )
