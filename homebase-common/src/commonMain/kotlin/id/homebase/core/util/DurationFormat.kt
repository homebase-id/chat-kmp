package id.homebase.core.util

/** Milliseconds → `m:ss` (or `h:mm:ss` past an hour). Locale-independent. */
fun formatHms(ms: Long, padMinutes: Boolean = false, showHours: Boolean = true): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000L
    val hours = if (showHours) totalSeconds / 3600L else 0L
    val minutes = (totalSeconds - hours * 3600L) / 60L
    val seconds = totalSeconds % 60L
    val ss = seconds.toString().padStart(2, '0')
    return when {
        hours > 0L -> "$hours:${minutes.toString().padStart(2, '0')}:$ss"
        padMinutes -> "${minutes.toString().padStart(2, '0')}:$ss"
        else -> "$minutes:$ss"
    }
}
