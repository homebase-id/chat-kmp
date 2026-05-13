package id.homebase.core.util

import kotlin.time.Instant


actual fun formatShortDate(instant: Instant): String {
    return formatShortDateJs(instant.toEpochMilliseconds())
}


actual fun formatTime(instant: Instant): String  {
    return formatTimeJs(instant.toEpochMilliseconds())
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