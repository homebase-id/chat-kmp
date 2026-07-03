package id.homebase.core.ui.screens.location

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the 60s freshness contract for the "who I can locate" temporal verify (#950).
 *
 * Regression: the expand guard skipped every already-resolved row for the ViewModel's whole
 * lifetime, so the verify only ever ran once per contact — a re-expand showed no spinner and a
 * frozen first-expand age. The guard now keys on [needsReverify]: in-flight still short-circuits
 * (no double-fire), a resolved result younger than [LOCATE_VERIFY_TTL_MS] is reused (instant
 * re-expand by design), and anything older — or never verified / dropped as inconclusive —
 * re-issues the verify.
 */
class LocateVerifyFreshnessTest {

    private val now = 1_000_000_000L

    @Test
    fun neverVerifiedReverifies() {
        assertTrue((null as LocateVerifyStatus?).needsReverify(now))
    }

    @Test
    fun inFlightNeverDoubleFires() {
        assertFalse(LocateVerifyStatus.Loading.needsReverify(now))
        // Even a stale-looking in-flight marker must not re-fire.
        assertFalse(LocateVerifyStatus.Loading.needsReverify(now + 10 * LOCATE_VERIFY_TTL_MS))
    }

    @Test
    fun freshResultsAreReused() {
        val justVerified = now - 1_000
        assertFalse(LocateVerifyStatus.Active(newestModifiedMs = 123L, verifiedAtMs = justVerified).needsReverify(now))
        assertFalse(LocateVerifyStatus.Active(newestModifiedMs = null, verifiedAtMs = justVerified).needsReverify(now))
        assertFalse(LocateVerifyStatus.Broken(verifiedAtMs = justVerified).needsReverify(now))
    }

    @Test
    fun staleResultsReverify() {
        val stale = now - LOCATE_VERIFY_TTL_MS - 1
        assertTrue(LocateVerifyStatus.Active(newestModifiedMs = 123L, verifiedAtMs = stale).needsReverify(now))
        assertTrue(LocateVerifyStatus.Broken(verifiedAtMs = stale).needsReverify(now))
    }

    @Test
    fun exactlyTtlOldReverifies() {
        val atTtl = now - LOCATE_VERIFY_TTL_MS
        assertTrue(LocateVerifyStatus.Active(newestModifiedMs = 123L, verifiedAtMs = atTtl).needsReverify(now))
    }

    @Test
    fun justUnderTtlIsReused() {
        val justUnder = now - LOCATE_VERIFY_TTL_MS + 1
        assertFalse(LocateVerifyStatus.Active(newestModifiedMs = 123L, verifiedAtMs = justUnder).needsReverify(now))
    }

    // Unreachable (verify threw, #879) is Resolved: a network blip is retried after the TTL like
    // any other result, instead of the old drop-the-key-and-blank behavior.

    @Test
    fun freshUnreachableIsNotRetriedYet() {
        assertFalse(LocateVerifyStatus.Unreachable(verifiedAtMs = now - 1_000).needsReverify(now))
    }

    @Test
    fun staleUnreachableRetries() {
        assertTrue(LocateVerifyStatus.Unreachable(verifiedAtMs = now - LOCATE_VERIFY_TTL_MS).needsReverify(now))
    }
}
