package id.homebase.core.util

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import android.text.format.DateFormat as AndroidDateFormat

actual fun formatShortDate(instant: Instant): String {
    val date = Date.from(instant.toJavaInstant())
    return DateFormat.getDateInstance(DateFormat.SHORT).format(date)
}

actual fun formatTime(instant: Instant): String {
    val date = Date.from(instant.toJavaInstant())
    return DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
}

actual fun formatMediumDate(instant: Instant): String {
    val date = Date.from(instant.toJavaInstant())
    return DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(date)
}

actual fun formatMediumDateNoYear(instant: Instant): String {
    // Android has a skeleton-pattern API that returns the locale's preferred
    // day/month ordering: "MMM d" for en-US, "d MMM" for fr-FR, "d. MMM" for de-DE.
    val pattern = AndroidDateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMd")
    val date = Date.from(instant.toJavaInstant())
    return SimpleDateFormat(pattern, Locale.getDefault()).format(date)
}