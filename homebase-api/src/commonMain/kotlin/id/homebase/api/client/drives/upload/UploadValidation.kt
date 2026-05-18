package id.homebase.api.client.drives.upload

import id.homebase.api.HomebaseProtocol
import id.homebase.api.client.ClientException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.ProblemDetails
import id.homebase.api.client.drives.files.PayloadFile

/**
 * Client-side pre-flight validation for upload requests.
 *
 * Mirrors invariants the server enforces, so we can fail fast without
 * burning a network round-trip and (more importantly) catch buggy
 * thumbnail / payload producers at the outbox edge rather than relying
 * on the server's 400 → permanent-failure regex to clean up after them.
 *
 * Thrown as a [ClientException] with status 400 and a message prefixed
 * with [VALIDATION_MESSAGE_PREFIX], which
 * [id.homebase.api.sync.database.OutboxSync]'s `isPermanentFailure`
 * matches so the existing outbox-drop machinery handles the failure on
 * attempt 1.
 *
 * Why a thrown exception instead of a returned Result: the validator
 * runs from inside [DriveOutboxUploader], which already converts
 * ClientExceptions into permanent-failure drops. Throwing keeps that
 * one path intact instead of forking it.
 */
const val VALIDATION_MESSAGE_PREFIX = "Upload validation failed: "

internal const val PAYLOAD_KEY_PATTERN_RAW = "^[a-z0-9_]{8,10}$"
private val PAYLOAD_KEY_PATTERN = Regex(PAYLOAD_KEY_PATTERN_RAW)

fun UploadFileRequest.validateForUpload() {
    validateMetadata(metadata)
    payloads.forEach { validatePayload(it) }
}

fun UpdateFileByUniqueIdRequest.validateForUpload() {
    validateMetadata(metadata)
    payloads?.forEach { validatePayload(it) }
}

private fun validateMetadata(metadata: UploadFileMetadata) {
    metadata.appData.previewThumbnail?.let { thumb ->
        validateEmbeddedThumb(thumb, where = "metadata.appData.previewThumbnail")
    }
    metadata.appData.content?.let { content ->
        // appData.content is the header-level descriptor. Server caps it
        // at MaxHeaderContentBytes — typed-message-kind authors are
        // already warned about this in ADDING_TYPED_MESSAGE_KIND.md, but
        // until now nothing actually enforced it on the client.
        val size = content.encodeToByteArray().size
        if (size > HomebaseProtocol.MaxHeaderContentBytes) {
            throw validationFailure(
                "Header content size of $size exceeds ${HomebaseProtocol.MaxHeaderContentBytes}"
            )
        }
    }
}

private fun validatePayload(payload: PayloadFile) {
    if (!PAYLOAD_KEY_PATTERN.matches(payload.key)) {
        // Server returns 400 with this exact shape; producing it
        // verbatim means callers debugging the log get the same string
        // they'd see from a server-rejected upload.
        throw validationFailure(
            "Payload key '${payload.key}' does not match $PAYLOAD_KEY_PATTERN_RAW"
        )
    }
    payload.previewThumbnail?.let { thumb ->
        validateEmbeddedThumb(thumb, where = "payload[${payload.key}].previewThumbnail")
    }
    payload.descriptorContent?.let { descriptor ->
        val size = descriptor.encodeToByteArray().size
        if (size > HomebaseProtocol.MaxPayloadDescriptorBytes) {
            throw validationFailure(
                "Payload descriptor size of $size exceeds ${HomebaseProtocol.MaxPayloadDescriptorBytes}"
            )
        }
    }
}

private fun validateEmbeddedThumb(thumb: EmbeddedThumb, where: String) {
    val content = thumb.content ?: return
    val rawSize = estimateRawBytesFromBase64(content)
    if (rawSize > HomebaseProtocol.MaxEmbeddedThumbBytes) {
        // The "size of N exceeds M" shape is matched by
        // OutboxSync.isPermanentFailure's regex (added in Phase 2) so
        // this throw cleanly converts into an attempt-1 drop. Including
        // [where] in the prefix lets log readers tell which producer
        // emitted the bad thumb without re-reading the message.
        throw validationFailure(
            "Thumbnail size of $rawSize exceeds ${HomebaseProtocol.MaxEmbeddedThumbBytes} ($where)"
        )
    }
}

// 4 base64 chars → 3 raw bytes; trailing '=' padding shaves 1 or 2
// off the last group. Cheaper than re-decoding the string just to count.
private fun estimateRawBytesFromBase64(content: String): Int {
    if (content.isEmpty()) return 0
    val padding = when {
        content.endsWith("==") -> 2
        content.endsWith("=") -> 1
        else -> 0
    }
    return content.length / 4 * 3 - padding
}

private fun validationFailure(reason: String): ClientException {
    val message = VALIDATION_MESSAGE_PREFIX + reason
    return ClientException(
        status = 400,
        errorCode = OdinClientErrorCode.UnhandledScenario,
        message = message,
        correlationId = null,
        problem = ProblemDetails(status = 400, title = message),
    )
}
