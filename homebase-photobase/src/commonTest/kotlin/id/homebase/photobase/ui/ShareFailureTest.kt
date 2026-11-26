package id.homebase.photobase.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareFailureTest {
    @Test
    fun anErrorOfThisDevicesOwnIsSaidAsOneWithItsTextUnderDetails() {
        val failure = ShareFailure.of(IllegalStateException("album 1 isn't on the drive"))

        assertNull(failure.reason, "its text isn't shown to the user")
        assertFalse(failure.unreachable)
        assertEquals("IllegalStateException: album 1 isn't on the drive", failure.deviceError)
        assertTrue(failure.hasDetails)
    }

    @Test
    fun aDroppedConnectionIsUnreachableWithNothingUnderDetails() {
        val failure = ShareFailure.of(kotlinx.io.IOException("connection reset"))

        assertTrue(failure.unreachable)
        assertFalse(failure.hasDetails)
    }

    @Test
    fun aServerErrorWithNoTitleHasNoQuotedReasonButItsStatusUnderDetails() {
        val failure = ShareFailure.of(
            id.homebase.api.client.ClientException(502, message = "Bad Gateway", correlationId = "abc", problem = id.homebase.api.client.ProblemDetails()),
        )

        assertNull(failure.reason, "the message carries the status and ids, which aren't for the snackbar")
        assertEquals(502, failure.status)
        assertEquals("abc", failure.correlationId)
    }
}
