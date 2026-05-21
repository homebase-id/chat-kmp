package id.homebase.core.util

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Instant
import kotlin.time.toJavaInstant

actual fun formatShortDate(instant: Instant): String {
    val date = Date.from(instant.toJavaInstant())
    return DateFormat.getDateInstance(DateFormat.SHORT, Locale.getDefault()).format(date)
}

actual fun formatTime(instant: Instant): String {
    val date = Date.from(instant.toJavaInstant())
    return DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(date)
}

actual fun formatMediumDate(instant: Instant): String {
    val date = Date.from(instant.toJavaInstant())
    return DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(date)
}

actual fun formatMediumDateNoYear(instant: Instant): String {
    // Java has no skeleton-pattern API; "MMM d" is the best we can do.
    // Reads correctly in English, slightly off in locales that prefer
    // day-first ordering (de, fr) — those users see "May 8" instead of "8. Mai".
    val date = Date.from(instant.toJavaInstant())
    return SimpleDateFormat("MMM d", Locale.getDefault()).format(date)
}
