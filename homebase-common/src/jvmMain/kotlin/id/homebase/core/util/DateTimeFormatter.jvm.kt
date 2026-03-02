package id.homebase.core.util

import java.text.DateFormat
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
