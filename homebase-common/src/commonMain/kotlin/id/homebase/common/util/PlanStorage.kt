package id.homebase.common.util

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * How full a plan is, in words people read without converting units: the share used as a percentage of the plan's
 * size ("12% of 500 GB"), and what is used and free in the plan's own unit ("60 GB used · 440 GB free"), never
 * "85.2 MB of 500.0 GB". Keep in step with odin-js planStorage.ts.
 *
 * @property percent Whole percent used: 0 when nothing is, 99 until it is actually full, 100 when it is; null for
 *   under 1%
 * @property size The plan's size: "500 GB", "1 TB", "1.5 TB"
 * @property used Used, in the plan's unit: "0.1 GB"
 * @property usedIsUnder More than nothing is used, but it rounds to nothing: [used] is the smallest step, to say
 *   "under" it
 * @property free Free, in the plan's unit: "499.9 GB"; "0 GB" when it is full
 */
data class PlanStorageText(
    val percent: Int?,
    val size: String,
    val used: String,
    val usedIsUnder: Boolean,
    val free: String,
)

private val UNITS = listOf("B", "KB", "MB", "GB", "TB", "PB")

fun planStorageText(usedBytes: Long, quotaBytes: Long): PlanStorageText {
    var unit = 0
    while (unit < UNITS.lastIndex && quotaBytes >= 1024.0.pow(unit + 1)) unit++
    val scale = 1024.0.pow(unit)
    // Two decimals for a plan under 10 of its unit (1 TB), so 0.29 TB is not 0.3 TB; one otherwise
    val decimals = when {
        unit == 0 -> 0
        quotaBytes / scale < 10 -> 2
        else -> 1
    }
    fun amount(bytes: Double, places: Int) = "${formatDecimal(bytes / scale, places)} ${UNITS[unit]}"

    val used = max(0L, usedBytes)
    val roundsToNothing = used > 0 && (used / scale * 10.0.pow(decimals)).roundToLong() == 0L
    return PlanStorageText(
        percent = percentUsed(used, quotaBytes),
        size = amount(quotaBytes.toDouble(), 1),
        used = amount(if (roundsToNothing) 10.0.pow(-decimals) * scale else used.toDouble(), decimals),
        usedIsUnder = roundsToNothing,
        free = amount(max(0L, quotaBytes - used).toDouble(), decimals),
    )
}

private fun percentUsed(used: Long, quota: Long): Int? {
    if (quota <= 0 || used >= quota) return 100
    if (used == 0L) return 0
    val percent = used.toDouble() / quota * 100
    return if (percent < 1) null else minOf(99, percent.roundToLong().toInt())
}

// 500.0 -> "500", 0.10 -> "0.1": at most [places] decimals, no trailing zeros
internal fun formatDecimal(value: Double, places: Int): String {
    val factor = 10.0.pow(places).toLong()
    val scaled = (value * factor).roundToLong()
    val fraction = (scaled % factor).toString().padStart(places, '0').trimEnd('0')
    return if (fraction.isEmpty()) "${scaled / factor}" else "${scaled / factor}.$fraction"
}
