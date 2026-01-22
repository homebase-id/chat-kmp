package id.homebase.core.util

actual fun formatShortDate(instant: kotlinx.datetime.Instant): String {
    val date = js("new Date(instant.toEpochMilliseconds())")
    return js("date.toLocaleDateString()").toString()
}

actual fun formatTime(instant: kotlinx.datetime.Instant): String {
    val date = js("new Date(instant.toEpochMilliseconds())")
    return js("date.toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'})").toString()
}