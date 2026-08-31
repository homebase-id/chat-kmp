package id.homebase.api.client

import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the [isRecoverablePermissionFailure] predicate the platform uncaught-exception
 * handlers use to keep a server 403 from killing the process.
 *
 * The observed crash: with the app token's drive grants revoked server-side, an add-on
 * activation writes the drive-registry file on the Chat drive, the server answers 403, and the
 * exception leaves a bare `viewModelScope.launch` with no CoroutineExceptionHandler — straight
 * to `Thread.setDefaultUncaughtExceptionHandler` and process death, four times in 72 seconds.
 *
 * The predicate must be precise: a 403 classifies true (recorded, app survives), every other
 * failure — a 401, a programming error — classifies false and stays fatal.
 */
class ForbiddenExceptionTest {

    private fun forbidden(title: String) = ForbiddenException(
        ProblemDetails(status = 403, title = title),
    )

    @Test
    fun `a 403 classifies as a recoverable permission failure`() {
        assertTrue(
            forbidden("No access permitted to drive 9ff813af-f2d6-1e2f-9b9d-b189e72d1a11")
                .isRecoverablePermissionFailure(),
        )
    }

    @Test
    fun `a 403 nested in a cause chain classifies`() {
        // The image loader wraps the real cause in a RuntimeException before rethrowing.
        val wrapped = RuntimeException("thumbnail load failed", forbidden("Does not have permission"))
        assertTrue(wrapped.isRecoverablePermissionFailure())
    }

    @Test
    fun `a cyclic cause chain terminates`() {
        val outer = RuntimeException("outer")
        val inner = RuntimeException("inner", outer)
        outer.initCause(inner)
        assertFalse(outer.isRecoverablePermissionFailure())
    }

    @Test
    fun `a 401 stays fatal`() {
        // An invalid token is an auth-state problem the auth layer has to act on
        // (DeadTokenLogout), not something to keep running through.
        assertFalse(UnauthorizedException().isRecoverablePermissionFailure())
    }

    @Test
    fun `programming errors stay fatal`() {
        assertFalse(IllegalStateException("No active credentials set").isRecoverablePermissionFailure())
        assertFalse(NullPointerException().isRecoverablePermissionFailure())
        assertFalse(IllegalArgumentException("bad").isRecoverablePermissionFailure())
    }

    @Test
    fun `a transport failure is not misclassified as a permission failure`() {
        assertFalse(NetworkException(IOException("socket closed")).isRecoverablePermissionFailure())
    }

    @Test
    fun `a permission failure is not misclassified as transport or conflict`() {
        val ex = forbidden("No access permitted to drive")
        assertFalse(ex.isTransientNetworkFailure())
        assertFalse(ex.isRecoverableServerConflict())
    }
}
