package id.homebase.core.location

import id.homebase.resources.MR
import id.homebase.resources.live_share_15m
import id.homebase.resources.live_share_1h
import id.homebase.resources.live_share_2h
import id.homebase.resources.live_share_30m
import id.homebase.resources.live_share_4h
import id.homebase.resources.live_share_indefinite
import org.jetbrains.compose.resources.StringResource

/**
 * Reserved sentinel meaning "share until explicitly stopped" (#1013). Doubles as both the menu
 * *duration* value and the absolute *end-time* written to the descriptor/roster: because every
 * liveness check is `now < endTimeMs`, this end-time always reads LIVE, is never pruned by the
 * roster's expiry sweep (so it survives restarts), and ends only via explicit stop/stopAll.
 * UI must equality-match it and show "until stopped" — never feed it to
 * [formatLiveShareRemaining] or render it as a date.
 *
 * The value is a fixed far-future timestamp (2100-01-01T00:00Z) rather than Long.MAX_VALUE:
 * it stays exact when a non-Kotlin client parses the synced header as a JS double (< 2^53, so
 * cross-client equality checks work) and can't overflow if offset arithmetic ever touches it.
 */
const val LIVE_SHARE_INDEFINITE: Long = 4_102_444_800_000L

/**
 * The only correct way to turn a picked menu duration into an absolute share end-time.
 * The indefinite sentinel is a fixed point (it IS the end-time, not a duration) —
 * `now + LIVE_SHARE_INDEFINITE` would produce a meaningless, unrecognizable timestamp.
 */
fun liveShareEndTimeMs(nowMs: Long, durationMs: Long): Long =
    if (durationMs == LIVE_SHARE_INDEFINITE) LIVE_SHARE_INDEFINITE else nowMs + durationMs

/**
 * The selectable live-location share durations (label resource → duration millis), shared by the
 * chat bubble's duration menu and the share-location screen so both offer the identical set.
 * The last entry is the indefinite share (#1013): its value is [LIVE_SHARE_INDEFINITE], not a real
 * duration — convert picks with [liveShareEndTimeMs]. Backgrounded GPS freshness is governed
 * separately by #878.
 */
val LIVE_SHARE_DURATION_OPTIONS: List<Pair<StringResource, Long>> = listOf(
    MR.string.live_share_15m to 15 * 60_000L,
    MR.string.live_share_30m to 30 * 60_000L,
    MR.string.live_share_1h to 60 * 60_000L,
    MR.string.live_share_2h to 2 * 60 * 60_000L,
    MR.string.live_share_4h to 4 * 60 * 60_000L,
    MR.string.live_share_indefinite to LIVE_SHARE_INDEFINITE,
)

/** Compact "time left" label: "42m", "1h", "1h 20m". */
fun formatLiveShareRemaining(remainingMs: Long): String {
    val totalMin = (remainingMs / 60_000L).coerceAtLeast(0L)
    if (totalMin < 60) return "${totalMin}m"
    val h = totalMin / 60
    val m = totalMin % 60
    return if (m == 0L) "${h}h" else "${h}h ${m}m"
}
