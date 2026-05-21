package id.homebase.common.util

import kotlin.math.abs

private const val KB = 1024.0
private const val MB = KB * 1024.0
private const val GB = MB * 1024.0

/**
 * Renders a byte count as a short human-readable string like `"3.2 MB"`.
 * Uses binary (1024-based) units and one fractional digit. Negative inputs
 * are formatted with their sign preserved.
 */
fun formatBytes(bytes: Long): String {
    val absBytes = abs(bytes)
    return when {
        absBytes < KB -> "$bytes B"
        absBytes < MB -> formatUnit(bytes / KB, "KB")
        absBytes < GB -> formatUnit(bytes / MB, "MB")
        else -> formatUnit(bytes / GB, "GB")
    }
}

private fun formatUnit(value: Double, suffix: String): String {
    val rounded = (value * 10).toLong() / 10.0
    val whole = rounded.toLong()
    val tenths = ((rounded - whole) * 10).toLong()
    val tenthsAbs = if (tenths < 0) -tenths else tenths
    return "$whole.$tenthsAbs $suffix"
}
