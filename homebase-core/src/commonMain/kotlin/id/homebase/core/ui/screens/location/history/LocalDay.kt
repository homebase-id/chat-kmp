package id.homebase.core.ui.screens.location.history

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Device-local midnight of the day containing [epochMs]. */
fun localDayStart(epochMs: Long): Long {
    val tz = TimeZone.currentSystemDefault()
    return Instant.fromEpochMilliseconds(epochMs)
        .toLocalDateTime(tz).date
        .atStartOfDayIn(tz)
        .toEpochMilliseconds()
}

/** Local midnight [days] away from [dayStartMs] — DST-safe via LocalDate arithmetic. */
fun shiftDay(dayStartMs: Long, days: Int): Long {
    val tz = TimeZone.currentSystemDefault()
    return Instant.fromEpochMilliseconds(dayStartMs)
        .toLocalDateTime(tz).date
        .plus(days, DateTimeUnit.DAY)
        .atStartOfDayIn(tz)
        .toEpochMilliseconds()
}
