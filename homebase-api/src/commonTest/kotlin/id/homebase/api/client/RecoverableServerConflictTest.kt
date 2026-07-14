package id.homebase.api.client

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #1008: a recoverable 400 VersionTagMismatch that leaks to a scope without its own
 * CoroutineExceptionHandler must be contained by the platform crash handlers, not crash the app.
 * Guards the [isRecoverableServerConflict] classifier those handlers gate on.
 */
class RecoverableServerConflictTest {

    private fun clientException(
        errorCode: OdinClientErrorCode,
        message: String = "boom",
        cause: Throwable? = null,
    ) = ClientException(
        status = 400,
        errorCode = errorCode,
        message = message,
        correlationId = null,
        problem = ProblemDetails(status = 400, title = message),
        cause = cause,
    )

    @Test
    fun versionTagMismatch_isRecoverable() {
        assertTrue(clientException(OdinClientErrorCode.VersionTagMismatch).isRecoverableServerConflict())
    }

    @Test
    fun otherClientErrorCodes_areNotRecoverable() {
        assertFalse(clientException(OdinClientErrorCode.UnhandledScenario).isRecoverableServerConflict())
        assertFalse(clientException(OdinClientErrorCode.MaxContentLengthExceeded).isRecoverableServerConflict())
    }

    @Test
    fun versionTagMismatchWrappedInCause_isRecoverable() {
        val wrapped = RuntimeException("outer", clientException(OdinClientErrorCode.VersionTagMismatch))
        assertTrue(wrapped.isRecoverableServerConflict())
    }

    @Test
    fun plainThrowable_isNotRecoverable() {
        assertFalse(RuntimeException("bug").isRecoverableServerConflict())
        assertFalse(IllegalStateException("no credentials").isRecoverableServerConflict())
    }

    @Test
    fun doesNotOverlapWithTransientNetworkClassifier() {
        // A version conflict is a server response, not a transport failure — and vice versa.
        assertFalse(clientException(OdinClientErrorCode.VersionTagMismatch).isTransientNetworkFailure())
    }
}
