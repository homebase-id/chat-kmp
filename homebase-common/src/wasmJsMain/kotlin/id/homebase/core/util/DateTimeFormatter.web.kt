package id.homebase.core.util

import kotlin.time.Instant


actual fun formatShortDate(instant: Instant): String {
    return formatShortDateJs(instant.toEpochMilliseconds())
}


actual fun formatTime(instant: Instant): String  {
    return formatTimeJs(instant.toEpochMilliseconds())
}

actual fun formatMediumDate(instant: Instant): String {
    return formatMediumDateJs(instant.toEpochMilliseconds())
}

actual fun formatMediumDateNoYear(instant: Instant): String {
    return formatMediumDateNoYearJs(instant.toEpochMilliseconds())
}

fun formatShortDateJs(epochMs: Long): String = js(
    """{
        let date = new Date(Number(epochMs));
        return date.toLocaleDateString();
}"""
)

 fun formatTimeJs(epochMs: Long): String = js(
    """{
        let date = new Date(Number(epochMs));
        return date.toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'});
}"""
)

fun formatMediumDateJs(epochMs: Long): String = js(
    """{
        let date = new Date(Number(epochMs));
        return date.toLocaleDateString([], {year: 'numeric', month: 'short', day: 'numeric'});
}"""
)

fun formatMediumDateNoYearJs(epochMs: Long): String = js(
    """{
        let date = new Date(Number(epochMs));
        return date.toLocaleDateString([], {month: 'short', day: 'numeric'});
}"""
)