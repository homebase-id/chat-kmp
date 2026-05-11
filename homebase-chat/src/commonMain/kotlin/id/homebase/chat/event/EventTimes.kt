package id.homebase.chat.event

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Viewer-first timezone view of an Event.
 *
 * `viewer*` fields are always populated, in the device's current zone.
 * `authored*` fields are populated only when the event's authored zone
 * differs from the device zone — letting callers conditionally render a
 * "Scheduled: …" tagline that shows the organizer's intended clock without
 * displacing the viewer's local time.
 */
data class EventTimes(
    val viewerStartLocal: LocalDateTime,
    val viewerEndLocal: LocalDateTime?,
    val viewerTz: TimeZone,
    val authoredStartLocal: LocalDateTime?,
    val authoredEndLocal: LocalDateTime?,
    val authoredTzId: String?,
)

/**
 * Pure variant — testable. The composable [rememberEventTimes] is a thin
 * wrapper that supplies `TimeZone.currentSystemDefault()` and memoizes.
 */
fun computeEventTimes(descriptor: EventDescriptor, viewerTz: TimeZone): EventTimes {
    val authoredTz = runCatching { TimeZone.of(descriptor.timezone) }.getOrElse { viewerTz }
    val viewerStart = Instant.fromEpochMilliseconds(descriptor.startUtcMs).toLocalDateTime(viewerTz)
    val viewerEnd = descriptor.endUtcMs?.let {
        Instant.fromEpochMilliseconds(it).toLocalDateTime(viewerTz)
    }
    val zonesDiffer = authoredTz.id != viewerTz.id
    val authoredStart = if (zonesDiffer) {
        Instant.fromEpochMilliseconds(descriptor.startUtcMs).toLocalDateTime(authoredTz)
    } else null
    val authoredEnd = if (zonesDiffer) {
        descriptor.endUtcMs?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(authoredTz) }
    } else null
    return EventTimes(
        viewerStartLocal = viewerStart,
        viewerEndLocal = viewerEnd,
        viewerTz = viewerTz,
        authoredStartLocal = authoredStart,
        authoredEndLocal = authoredEnd,
        authoredTzId = if (zonesDiffer) authoredTz.id else null,
    )
}

@Composable
fun rememberEventTimes(descriptor: EventDescriptor): EventTimes =
    remember(descriptor.startUtcMs, descriptor.endUtcMs, descriptor.timezone) {
        computeEventTimes(descriptor, TimeZone.currentSystemDefault())
    }

/**
 * Convert a UTC instant to a date in the viewer's local zone. Used by the
 * reply chip's self-contained path, where only the start instant is
 * carried on the wire — authoring zone isn't needed because the chip
 * renders in the viewer's zone, and viewer→viewer is just a UTC→local
 * conversion.
 */
@Composable
fun rememberViewerLocalDate(startUtcMs: Long): LocalDateTime {
    val viewerTz = TimeZone.currentSystemDefault()
    return remember(startUtcMs, viewerTz) {
        Instant.fromEpochMilliseconds(startUtcMs).toLocalDateTime(viewerTz)
    }
}
