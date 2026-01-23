package id.homebase.homebasekmppoc.prototype.lib.core.time

import kotlinx.serialization.Serializable

/**
 * Represents a time range between start and end Unix timestamps
 *
 * Ported from C# Odin.Core.Time.UnixTimeUtcRange
 */
@Serializable
data class UnixTimeUtcRange(
    val start: UnixTimeUtc,
    val end: UnixTimeUtc
) {
    init {
        require(start < end) { "Start date must be less than end date" }
    }

    fun isValid(): Boolean = start < end

    fun validate() {
        if (!isValid()) {
            throw IllegalStateException("Start date must be less than end date")
        }
    }
}
