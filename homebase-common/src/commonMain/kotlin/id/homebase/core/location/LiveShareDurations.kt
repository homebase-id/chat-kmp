package id.homebase.core.location

import id.homebase.resources.MR
import id.homebase.resources.live_share_15m
import id.homebase.resources.live_share_1h
import id.homebase.resources.live_share_24h
import id.homebase.resources.live_share_2h
import id.homebase.resources.live_share_30m
import id.homebase.resources.live_share_4h
import org.jetbrains.compose.resources.StringResource

/**
 * The selectable live-location share durations (label resource → duration millis), shared by the
 * chat bubble's duration menu and the share-location screen so both offer the identical set.
 * 24h is the all-day option (festivals etc., #889) — the share window is absolute-endTime driven,
 * so it's just a larger value; backgrounded GPS freshness is governed separately by #878.
 */
val LIVE_SHARE_DURATION_OPTIONS: List<Pair<StringResource, Long>> = listOf(
    MR.string.live_share_15m to 15 * 60_000L,
    MR.string.live_share_30m to 30 * 60_000L,
    MR.string.live_share_1h to 60 * 60_000L,
    MR.string.live_share_2h to 2 * 60 * 60_000L,
    MR.string.live_share_4h to 4 * 60 * 60_000L,
    MR.string.live_share_24h to 24L * 60 * 60_000L,
)

/** Compact "time left" label: "42m", "1h", "1h 20m". */
fun formatLiveShareRemaining(remainingMs: Long): String {
    val totalMin = (remainingMs / 60_000L).coerceAtLeast(0L)
    if (totalMin < 60) return "${totalMin}m"
    val h = totalMin / 60
    val m = totalMin % 60
    return if (m == 0L) "${h}h" else "${h}h ${m}m"
}
