package id.homebase.core.location

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the #1013 indefinite-share contract: [liveShareEndTimeMs] is the only duration→end-time
 * conversion, and the [LIVE_SHARE_INDEFINITE] sentinel is its fixed point — it must never flow
 * through `now + duration` (that would yield an unrecognizable timestamp no consumer could
 * special-case as "until stopped").
 */
class LiveShareDurationsTest {

    @Test
    fun finiteDurationCountsFromNow() {
        assertEquals(10_000L + 15 * 60_000L, liveShareEndTimeMs(nowMs = 10_000L, durationMs = 15 * 60_000L))
    }

    @Test
    fun indefiniteSentinelIsAFixedPointNotAnOffset() {
        assertEquals(LIVE_SHARE_INDEFINITE, liveShareEndTimeMs(nowMs = 10_000L, durationMs = LIVE_SHARE_INDEFINITE))
    }

    @Test
    fun sentinelIsTheReserved2100Timestamp() {
        // 2100-01-01T00:00Z — fixed so every consumer (and non-Kotlin clients parsing the synced
        // header as a double, < 2^53) can equality-match it. Changing it breaks recognition of
        // already-sent indefinite shares.
        assertEquals(4_102_444_800_000L, LIVE_SHARE_INDEFINITE)
    }

    @Test
    fun pickerEndsWithTheIndefiniteOptionAndStaysSixEntries() {
        assertEquals(6, LIVE_SHARE_DURATION_OPTIONS.size)
        assertEquals(LIVE_SHARE_INDEFINITE, LIVE_SHARE_DURATION_OPTIONS.last().second)
        // Every other entry is a real (small) relative duration, safe for `now + duration`.
        LIVE_SHARE_DURATION_OPTIONS.dropLast(1).forEach { (_, durationMs) ->
            check(durationMs in 1..24L * 60 * 60_000L) { "unexpected duration $durationMs" }
        }
    }
}
