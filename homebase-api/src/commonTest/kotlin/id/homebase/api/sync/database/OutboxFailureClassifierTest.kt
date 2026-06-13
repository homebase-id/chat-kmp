package id.homebase.api.sync.database

import id.homebase.api.client.ClientException
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.ProblemDetails
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private fun clientException(
    status: Int = 400,
    errorCode: OdinClientErrorCode = OdinClientErrorCode.UnhandledScenario,
    message: String,
): ClientException = ClientException(
    status = status,
    errorCode = errorCode,
    message = message,
    correlationId = null,
    problem = ProblemDetails(status = status, title = message),
)

/**
 * Unit tests for [classifyPermanentFailure] — the single classification point
 * for "this outbox failure will never be fixed by retrying". Each permanent
 * case must return a non-null reason; retryable cases must return null.
 *
 * The string-match cases each correspond to a server behavior where the
 * structured errorCode is collapsed to UnhandledScenario but the message text
 * survives — see the comments in OutboxFailureClassifier.kt for the incident
 * each one was added for.
 */
class OutboxFailureClassifierTest {

    // ---- permanent: exception type ----

    @Test
    fun notFoundExceptionIsPermanent() {
        assertNotNull(classifyPermanentFailure(NotFoundException()))
    }

    // ---- permanent: structured error codes ----

    @Test
    fun permanentErrorCodesArePermanent() {
        val permanentCodes = listOf(
            OdinClientErrorCode.FileNotFound,
            OdinClientErrorCode.MissingVersionTag,
            OdinClientErrorCode.VersionTagMismatch,
            OdinClientErrorCode.CannotOverwriteNonExistentFile,
            OdinClientErrorCode.UnknownId,
        )
        for (code in permanentCodes) {
            assertNotNull(
                classifyPermanentFailure(clientException(errorCode = code, message = "irrelevant")),
                "errorCode=$code must classify as permanent",
            )
        }
    }

    // ---- permanent: message text with errorCode collapsed to UnhandledScenario ----

    @Test
    fun couldNotFindFileMessageIsPermanent() {
        assertNotNull(
            classifyPermanentFailure(
                clientException(message = "Could not find file with uniqueId 7373d519-d042-d100-4aad-a8e5d48dd851")
            )
        )
    }

    @Test
    fun missingVersionTagMessageIsPermanent() {
        assertNotNull(
            classifyPermanentFailure(clientException(message = "Missing version tag for file update"))
        )
    }

    @Test
    fun mismatchingVersionTagMessageIsPermanent() {
        assertNotNull(
            classifyPermanentFailure(
                clientException(message = "Mismatching version tag 7373d519-d042-d100-4aad-a8e5d48dd851")
            )
        )
    }

    @Test
    fun sizeExceedsMessageIsPermanent() {
        assertNotNull(
            classifyPermanentFailure(clientException(message = "Thumbnail size of 1634 exceeds 1024"))
        )
    }

    @Test
    fun aesKeyMismatchMessageIsPermanent() {
        assertNotNull(
            classifyPermanentFailure(
                clientException(
                    message = "When updating an encrypted file, the AES key must match the existing key. " +
                        "Changing the AES key can invalidate existing encrypted payloads."
                )
            )
        )
    }

    @Test
    fun mustChangeIvMessageIsPermanent() {
        // The image-to-Leela stall (homebase.log 2026-06-13): a payload update
        // that replays the original IV is rejected with the errorCode collapsed
        // to UnhandledScenario but this title intact. Before the fix this
        // returned null and the row looped 20× over ~48h.
        assertNotNull(
            classifyPermanentFailure(
                clientException(message = "When updating a file, you must change the Iv")
            )
        )
    }

    @Test
    fun mustRotateIvErrorCodeIsPermanent() {
        assertNotNull(
            classifyPermanentFailure(
                clientException(
                    errorCode = OdinClientErrorCode.MustRotateKeyHeaderIvWhenUpdating,
                    message = "irrelevant",
                )
            )
        )
    }

    @Test
    fun uploadValidationFailureIsPermanent() {
        assertNotNull(
            classifyPermanentFailure(
                clientException(message = "Upload validation failed: payload key 'abc' exceeds 10 chars")
            )
        )
    }

    /**
     * "Cannot transfer to yourself": the outbox item's recipient list contains
     * the logged-in identity; the server rejects this forever. Previously this
     * was swallowed inside DriveOutboxUploader.upload by returning normally —
     * which made OutboxSync emit ItemCompleted (fake success). The classifier
     * owns it now so the drop is honest (OutboxItemDropped).
     */
    @Test
    fun cannotTransferToYourselfIsPermanent() {
        assertNotNull(
            classifyPermanentFailure(
                clientException(message = "Cannot transfer to yourself: frodo.dotyou.cloud")
            )
        )
    }

    // ---- retryable: must return null ----

    @Test
    fun genericServerErrorIsRetryable() {
        assertNull(
            classifyPermanentFailure(
                clientException(status = 500, message = "Internal server error")
            )
        )
    }

    @Test
    fun unmatchedBadRequestIsRetryable() {
        assertNull(
            classifyPermanentFailure(
                clientException(message = "Some transient condition the server reports as 400")
            )
        )
    }

    @Test
    fun plainExceptionIsRetryable() {
        assertNull(classifyPermanentFailure(Exception("network blip")))
    }
}
