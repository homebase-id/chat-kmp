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

@OptIn(ExperimentalWasmJsInterop::class)
fun formatShortDateJs(epochMs: Long): String = js(
    """{
        let date = new Date(Number(epochMs));
        return date.toLocaleDateString();
}"""
)

@OptIn(ExperimentalWasmJsInterop::class)
 fun formatTimeJs(epochMs: Long): String = js(
    """{
        let date = new Date(Number(epochMs));
        return date.toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'});
}"""
)

@OptIn(ExperimentalWasmJsInterop::class)
fun formatMediumDateJs(epochMs: Long): String = js(
    """{
        let date = new Date(Number(epochMs));
        return date.toLocaleDateString([], {year: 'numeric', month: 'short', day: 'numeric'});
}"""
)

@OptIn(ExperimentalWasmJsInterop::class)
fun formatMediumDateNoYearJs(epochMs: Long): String = js(
    """{
        let date = new Date(Number(epochMs));
        return date.toLocaleDateString([], {month: 'short', day: 'numeric'});
}"""
)