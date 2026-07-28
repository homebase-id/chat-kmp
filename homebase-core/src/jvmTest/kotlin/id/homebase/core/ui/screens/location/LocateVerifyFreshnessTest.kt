package id.homebase.core.ui.screens.location

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the 60s freshness contract for the "who I can locate" temporal verify (#950, refined by
 * #985).
 *
 * #950 regression: the expand guard skipped every already-resolved row for the ViewModel's whole
 * lifetime, so the verify only ever ran once per contact — a re-expand showed no spinner and a
 * frozen first-expand age. The guard keys on [needsReverify]: in-flight still short-circuits
 * (no double-fire), and a never-verified row always fires.
 *
 * #985 refinement: the TTL cache applies ONLY to a successful, DATA-BEARING [LocateVerifyStatus.Active]
 * ("they sent data back within 60s"). [LocateVerifyStatus.Broken], [LocateVerifyStatus.Unreachable],
 * and a no-data Active re-verify on EVERY pass — previously a broken-cloud row was cached like a
 * success and stuck across collapse/expand within the TTL.
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
    fun freshDataBearingActiveIsReused() {
        val justVerified = now - 1_000
        assertFalse(LocateVerifyStatus.Active(newestModifiedMs = 123L, verifiedAtMs = justVerified).needsReverify(now))
    }

    @Test
    fun freshErrorAndNoDataStatesReverify() {
        // #985: only "they sent data back" earns the cache — a fresh Broken/Unreachable/no-data
        // result re-verifies on the very next expansion so it clears the moment access returns.
        val justVerified = now - 1_000
        assertTrue(LocateVerifyStatus.Broken(verifiedAtMs = justVerified).needsReverify(now))
        assertTrue(LocateVerifyStatus.Unreachable(verifiedAtMs = justVerified).needsReverify(now))
        assertTrue(LocateVerifyStatus.Active(newestModifiedMs = null, verifiedAtMs = justVerified).needsReverify(now))
    }

    @Test
    fun staleResultsReverify() {
        val stale = now - LOCATE_VERIFY_TTL_MS - 1
        assertTrue(LocateVerifyStatus.Active(newestModifiedMs = 123L, verifiedAtMs = stale).needsReverify(now))
        assertTrue(LocateVerifyStatus.Broken(verifiedAtMs = stale).needsReverify(now))
        assertTrue(LocateVerifyStatus.Unreachable(verifiedAtMs = stale).needsReverify(now))
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
}
