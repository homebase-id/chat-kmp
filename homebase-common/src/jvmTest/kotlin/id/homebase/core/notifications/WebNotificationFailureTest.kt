package id.homebase.core.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The developer test button exists to tell "notifications can't display here" apart from
 * "display works, delivery is broken", so every failure has to read as its own thing and an
 * unrecognised DOMException.name must not be swallowed into a generic message.
 */
class WebNotificationFailureTest {

    @Test
    fun `each known failure gets its own message`() {
        val codes = listOf(
            "NotAllowedError",
            "PermissionNotGranted",
            "NeedsInstall",
            "Unsupported",
            "NoServiceWorker",
            "TimeoutError",
        )
        assertEquals(codes.size, codes.map { describeWebNotificationFailure(it) }.toSet().size)
    }

    @Test
    fun `an unrecognised DOMException name survives into the message`() {
        assertTrue(describeWebNotificationFailure("TypeError").contains("TypeError"))
    }
}
