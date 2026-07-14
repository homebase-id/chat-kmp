package id.homebase.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Locks the background grant-reconcile prune set (#1079): given the live optional drives and the
 * freshly-resolved read grants, decide which live drives to unmount. Mandatory drives are never
 * pruned; grant comparison is hyphen/case-insensitive (normalized).
 */
class GrantReconcileTest {

    private val chat = Uuid.random()
    private val contacts = Uuid.random()
    private val granted1 = Uuid.random()
    private val revoked = Uuid.random()
    private val mandatory = setOf(chat, contacts)

    private fun norm(u: Uuid) = normalizeDriveId(u.toString())

    @Test
    fun prunesAnActiveOptionalDriveNotInTheGrantSet() {
        val active = setOf(chat, granted1, revoked)
        val granted = setOf(norm(granted1)) // chat is mandatory; revoked has no grant
        assertEquals(listOf(revoked), drivesToPrune(active, mandatory, granted))
    }

    @Test
    fun neverPrunesMandatoryDrivesEvenIfNotGranted() {
        val active = setOf(chat, contacts, granted1)
        val granted = setOf(norm(granted1)) // chat/contacts absent from grants but mandatory
        assertTrue(drivesToPrune(active, mandatory, granted).isEmpty())
    }

    @Test
    fun keepsAllWhenEveryActiveDriveIsGranted() {
        val active = setOf(chat, granted1)
        val granted = setOf(norm(granted1))
        assertTrue(drivesToPrune(active, mandatory, granted).isEmpty())
    }

    @Test
    fun emptyGrantSetPrunesEveryNonMandatoryDrive() {
        val active = setOf(chat, granted1, revoked)
        assertEquals(setOf(granted1, revoked), drivesToPrune(active, mandatory, emptySet()).toSet())
    }

    @Test
    fun grantMatchIsHyphenAndCaseInsensitive() {
        // grant stored normalized (lowercased, hyphen-stripped); active drive id has hyphens/upper.
        val active = setOf(granted1)
        val granted = setOf(granted1.toString().lowercase().replace("-", ""))
        assertTrue(drivesToPrune(active, mandatory, granted).isEmpty())
    }
}
