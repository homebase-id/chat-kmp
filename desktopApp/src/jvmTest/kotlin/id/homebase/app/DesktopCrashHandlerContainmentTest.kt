package id.homebase.app

import id.homebase.api.client.ClientException
import id.homebase.api.client.ForbiddenException
import id.homebase.api.client.NetworkException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.ProblemDetails
import id.homebase.api.client.UnauthorizedException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards [isContainableNonFatal] — the decision the desktop uncaught-exception handler makes
 * before it shows the crash-recovery dialog and terminates. The Android twin is
 * `GlobalCrashHandlerContainmentTest`.
 *
 * The 403 case is the desktop crash loop: with the app token's drive grants revoked
 * server-side, an add-on activation writes the drive-registry file on the Chat drive, the
 * server answers 403, and the exception leaves a bare `viewModelScope.launch` — which on
 * desktop is `Dispatchers.Main.immediate`, i.e. the AWT EDT — with no handler. Four process
 * deaths in 72 seconds.
 */
class DesktopCrashHandlerContainmentTest {

    private val forbidden = ForbiddenException(
        ProblemDetails(
            status = 403,
            title = "No access permitted to drive 9ff813af-f2d6-1e2f-9b9d-b189e72d1a11",
        ),
    )

    @Test
    fun permissionDeniedIsContained() {
        assertTrue(isContainableNonFatal(forbidden))
    }

    @Test
    fun permissionDeniedNestedInACauseChainIsContained() {
        assertTrue(isContainableNonFatal(RuntimeException("upload failed", forbidden)))
    }

    @Test
    fun transientNetworkFailureIsStillContained() {
        assertTrue(isContainableNonFatal(NetworkException(IOException("socket closed"))))
    }

    @Test
    fun versionTagMismatchIsStillContained() {
        assertTrue(
            isContainableNonFatal(
                ClientException(
                    status = 400,
                    errorCode = OdinClientErrorCode.VersionTagMismatch,
                    message = "stale versionTag",
                    correlationId = null,
                    problem = ProblemDetails(status = 400, title = "stale versionTag"),
                ),
            ),
        )
    }

    @Test
    fun unauthorizedStillCrashes() {
        // A dead token is for the auth layer to act on, not something to keep running through.
        assertFalse(isContainableNonFatal(UnauthorizedException()))
    }

    @Test
    fun programmingErrorsStillCrash() {
        assertFalse(isContainableNonFatal(IllegalStateException("No active credentials set")))
        assertFalse(isContainableNonFatal(NullPointerException()))
    }
}
