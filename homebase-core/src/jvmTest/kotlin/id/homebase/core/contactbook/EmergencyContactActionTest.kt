package id.homebase.core.contactbook

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the receive-side decision tables for [EmergencyContactReceiveService] — in particular the
 * replay-guard rule that an unknown sender is synced but NOT consumed (so the flag still lands on a
 * later delivery), while a known sender is consumed to neutralise re-deliveries.
 */
class EmergencyContactActionTest {

    // ── Designation ─────────────────────────────────────────────────────────────

    @Test
    fun designation_unknownSender_syncsWithoutConsuming() {
        // contact doesn't exist yet → sync only; do NOT consume (flag applies on a later delivery).
        assertEquals(
            DesignationAction.SyncOnly,
            designationAction(isSelf = false, contactExists = false, alreadyICanLocate = false, hasVersionTag = false),
        )
    }

    @Test
    fun designation_alreadyFlagged_consumesOnly() {
        assertEquals(
            DesignationAction.Consume,
            designationAction(isSelf = false, contactExists = true, alreadyICanLocate = true, hasVersionTag = true),
        )
    }

    @Test
    fun designation_knownUnflaggedWithVersion_setsThenConsumes() {
        assertEquals(
            DesignationAction.SetThenConsume,
            designationAction(isSelf = false, contactExists = true, alreadyICanLocate = false, hasVersionTag = true),
        )
    }

    @Test
    fun designation_knownUnflaggedNoVersion_isIgnored() {
        // Can't write without a versionTag; leave it for a later delivery / reconcile.
        assertEquals(
            DesignationAction.Ignore,
            designationAction(isSelf = false, contactExists = true, alreadyICanLocate = false, hasVersionTag = false),
        )
    }

    @Test
    fun designation_fromSelf_consumesWithoutFlagging() {
        assertEquals(
            DesignationAction.Consume,
            designationAction(isSelf = true, contactExists = true, alreadyICanLocate = false, hasVersionTag = true),
        )
    }

    // ── Revocation ──────────────────────────────────────────────────────────────

    @Test
    fun revocation_unknownSender_consumesOnly() {
        assertEquals(
            RevocationAction.Consume,
            revocationAction(contactExists = false, currentlyICanLocate = false, hasVersionTag = false),
        )
    }

    @Test
    fun revocation_alreadyClear_consumesOnly() {
        assertEquals(
            RevocationAction.Consume,
            revocationAction(contactExists = true, currentlyICanLocate = false, hasVersionTag = true),
        )
    }

    @Test
    fun revocation_flaggedWithVersion_clearsThenConsumes() {
        assertEquals(
            RevocationAction.ClearThenConsume,
            revocationAction(contactExists = true, currentlyICanLocate = true, hasVersionTag = true),
        )
    }

    @Test
    fun revocation_flaggedNoVersion_isIgnored() {
        assertEquals(
            RevocationAction.Ignore,
            revocationAction(contactExists = true, currentlyICanLocate = true, hasVersionTag = false),
        )
    }
}
