package id.homebase.chat.groodle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Viewer-first timezone view of a single Groodle slot. Mirrors
 * `event/EventTimes.kt`, but per slot rather than per descriptor (a Groodle has
 * one authoring zone shared across all its slots).
 *
 * `viewer*` fields are always populated, in the device's current zone.
 * `authored*` fields are populated only when the authoring zone differs from the
 * device zone — letting the slot row render the viewer's local clock with a
 * "Scheduled in <city>" tagline showing the organizer's intended wall-clock.
 */
data class GroodleSlotTimes(
    val viewerStartLocal: LocalDateTime,
    val viewerEndLocal: LocalDateTime?,
    val viewerTz: TimeZone,
    val authoredStartLocal: LocalDateTime?,
    val authoredTzId: String?,
)

/** Pure variant — testable. */
fun computeSlotTimes(slot: GroodleSlot, authoredZoneId: String, viewerTz: TimeZone): GroodleSlotTimes {
    val authoredTz = runCatching { TimeZone.of(authoredZoneId) }.getOrElse { viewerTz }
    val viewerStart = Instant.fromEpochMilliseconds(slot.startUtcMs).toLocalDateTime(viewerTz)
    val viewerEnd = slot.endUtcMs?.let {
        Instant.fromEpochMilliseconds(it).toLocalDateTime(viewerTz)
    }
    val zonesDiffer = authoredTz.id != viewerTz.id
    val authoredStart = if (zonesDiffer) {
        Instant.fromEpochMilliseconds(slot.startUtcMs).toLocalDateTime(authoredTz)
    } else null
    return GroodleSlotTimes(
        viewerStartLocal = viewerStart,
        viewerEndLocal = viewerEnd,
        viewerTz = viewerTz,
        authoredStartLocal = authoredStart,
        authoredTzId = if (zonesDiffer) authoredTz.id else null,
    )
}

@Composable
fun rememberSlotTimes(slot: GroodleSlot, authoredZoneId: String): GroodleSlotTimes =
    remember(slot.startUtcMs, slot.endUtcMs, authoredZoneId) {
        computeSlotTimes(slot, authoredZoneId, TimeZone.currentSystemDefault())
    }

/** "HH:MM" in 24h, from a LocalDateTime. */
fun formatHourMinute(local: LocalDateTime): String {
    val h = local.hour.toString().padStart(2, '0')
    val m = local.minute.toString().padStart(2, '0')
    return "$h:$m"
}
