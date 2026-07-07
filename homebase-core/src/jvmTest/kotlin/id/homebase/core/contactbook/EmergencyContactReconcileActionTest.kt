package id.homebase.core.contactbook

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [reconcileAction] — the decision table for the verify-based reconcile passes.
 *
 * The core contract (issue #961): a verify-based pass must NEVER clear the incoming
 * `iCanLocate` flag. A non-throwing `hasAccess=false` is not a trustworthy revocation signal —
 * it also fires on benign/ambiguous negatives (and possibly when the owner empties their own
 * outgoing emergency circle). The only clear path is the peer's explicit revocation
 * ([EmergencyContactReceiveService.onRevoked] via [revocationAction]).
 */
class EmergencyContactReconcileActionTest {

    @Test
    fun `no access on a flagged contact does nothing - the 961 wipe regression pin`() {
        assertEquals(
            ReconcileAction.None,
            reconcileAction(hasAccess = false, flagged = true),
        )
    }

    @Test
    fun `access on an unflagged contact recovers the missed designation`() {
        assertEquals(
            ReconcileAction.Set,
            reconcileAction(hasAccess = true, flagged = false),
        )
    }

    @Test
    fun `access on an already-flagged contact does nothing`() {
        assertEquals(
            ReconcileAction.None,
            reconcileAction(hasAccess = true, flagged = true),
        )
    }

    @Test
    fun `no access on an unflagged contact does nothing`() {
        assertEquals(
            ReconcileAction.None,
            reconcileAction(hasAccess = false, flagged = false),
        )
    }

    @Test
    fun `a failed preflight is inconclusive and never acts`() {
        for (flagged in listOf(true, false)) {
            assertEquals(
                ReconcileAction.None,
                reconcileAction(hasAccess = null, flagged = flagged),
            )
        }
    }
}
