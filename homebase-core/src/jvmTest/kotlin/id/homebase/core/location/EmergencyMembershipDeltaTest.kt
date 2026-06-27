package id.homebase.core.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the membership-diff that drives [EmergencyCircleNotifier]: a null baseline never notifies
 * (no login re-spam), and grants/revocations are the set difference.
 */
class EmergencyMembershipDeltaTest {

    @Test
    fun nullBaseline_yieldsEmptyDelta() {
        val delta = emergencyMembershipDelta(previous = null, current = setOf("sam.example", "amy.example"))
        assertTrue(delta.granted.isEmpty(), "first loaded set must not be treated as new grants")
        assertTrue(delta.revoked.isEmpty())
    }

    @Test
    fun unchangedSet_yieldsEmptyDelta() {
        val members = setOf("sam.example")
        val delta = emergencyMembershipDelta(previous = members, current = members)
        assertTrue(delta.granted.isEmpty())
        assertTrue(delta.revoked.isEmpty())
    }

    @Test
    fun addedMember_isGranted() {
        val delta = emergencyMembershipDelta(
            previous = setOf("sam.example"),
            current = setOf("sam.example", "amy.example"),
        )
        assertEquals(setOf("amy.example"), delta.granted)
        assertTrue(delta.revoked.isEmpty())
    }

    @Test
    fun removedMember_isRevoked() {
        val delta = emergencyMembershipDelta(
            previous = setOf("sam.example", "amy.example"),
            current = setOf("sam.example"),
        )
        assertTrue(delta.granted.isEmpty())
        assertEquals(setOf("amy.example"), delta.revoked)
    }

    @Test
    fun simultaneousAddAndRemove_areBothReported() {
        val delta = emergencyMembershipDelta(
            previous = setOf("sam.example"),
            current = setOf("amy.example"),
        )
        assertEquals(setOf("amy.example"), delta.granted)
        assertEquals(setOf("sam.example"), delta.revoked)
    }

    @Test
    fun emptyBaseline_thenMembers_areAllGranted() {
        // An empty (but non-null) baseline is a real seeded state: everyone added since is a grant.
        val delta = emergencyMembershipDelta(previous = emptySet(), current = setOf("sam.example"))
        assertEquals(setOf("sam.example"), delta.granted)
        assertTrue(delta.revoked.isEmpty())
    }
}
