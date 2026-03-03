package id.homebase.core.util

import kotlin.math.log10
import kotlin.math.pow

/** Formats a file size in bytes to a human-readable string (e.g., "1.2 MB", "131 KB"). */
fun Long.formatFileSize(): String {
    if (this <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")
    val digitGroups = (log10(this.toDouble()) / log10(1024.0)).toInt()

    // Ensure we don't go out of bounds of the units array
    val mappedIndex = digitGroups.coerceIn(0, units.size - 1)

    val value = this / 1024.0.pow(mappedIndex.toDouble())

    // Format to 1 decimal place if not an exact integer and not bytes
    val formattedValue = if (mappedIndex == 0) {
        value.toInt().toString()
    } else {
        // Simple manual formatting to avoid platform-specific string formatters
        val rounded = kotlin.math.round(value * 10) / 10.0
        if (rounded % 1 == 0.0) {
            rounded.toInt().toString()
        } else {
            rounded.toString()
        }
    }

    return "$formattedValue ${units[mappedIndex]}"
}
