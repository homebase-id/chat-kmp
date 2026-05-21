package id.homebase.core.util

import kotlinx.datetime.toNSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import kotlin.time.Instant

actual fun formatShortDate(instant: Instant): String {
    val formatter = NSDateFormatter().apply {
        dateStyle = NSDateFormatterShortStyle
        timeStyle = NSDateFormatterNoStyle
    }
    return formatter.stringFromDate(instant.toNSDate())
}

actual fun formatTime(instant: Instant): String {
    val formatter = NSDateFormatter().apply {
        dateStyle = NSDateFormatterNoStyle
        timeStyle = NSDateFormatterShortStyle
    }
    return formatter.stringFromDate(instant.toNSDate())
}

actual fun formatMediumDate(instant: Instant): String {
    val formatter = NSDateFormatter().apply {
        dateStyle = NSDateFormatterMediumStyle
        timeStyle = NSDateFormatterNoStyle
    }
    return formatter.stringFromDate(instant.toNSDate())
}

actual fun formatMediumDateNoYear(instant: Instant): String {
    val formatter = NSDateFormatter().apply {
        // Skeleton template: NSDateFormatter rearranges into the locale's
        // preferred ordering (e.g. "MMM d" → "d MMM" in fr).
        setLocalizedDateFormatFromTemplate("MMMd")
    }
    return formatter.stringFromDate(instant.toNSDate())
}