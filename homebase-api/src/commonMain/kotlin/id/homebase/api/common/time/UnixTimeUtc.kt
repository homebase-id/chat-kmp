package id.homebase.homebasekmppoc.prototype.lib.core.time

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Represents a Unix timestamp in UTC with millisecond precision
 *
 * Ported from C# Odin.Core.Time.UnixTimeUtc
 */

object UnixTimeUtcSerializer : KSerializer<UnixTimeUtc> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("UnixTimeUtc", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: UnixTimeUtc) {
        encoder.encodeLong(value.milliseconds)
    }

    override fun deserialize(decoder: Decoder): UnixTimeUtc {
        return UnixTimeUtc(decoder.decodeLong())
    }
}

@Serializable(with = UnixTimeUtcSerializer::class)
data class UnixTimeUtc(val milliseconds: Long) : Comparable<UnixTimeUtc> {

    constructor() : this(Clock.System.now().toEpochMilliseconds())

    constructor(instant: Instant) : this(instant.toEpochMilliseconds())

    companion object {
        val ZeroTime = UnixTimeUtc(0)

        fun now(): UnixTimeUtc {
            return UnixTimeUtc()
        }

        fun fromInstant(instant: Instant): UnixTimeUtc {
            return UnixTimeUtc(instant)
        }
    }

    val seconds: Long
        get() = milliseconds / 1000

    /**
     * Returns a new UnixTimeUtc object with the seconds added
     */
    fun addSeconds(s: Long): UnixTimeUtc {
        return UnixTimeUtc(milliseconds + (s * 1000))
    }

    /**
     * Returns a new UnixTimeUtc object with the minutes added
     */
    fun addMinutes(m: Long): UnixTimeUtc {
        return UnixTimeUtc(milliseconds + (m * 60 * 1000))
    }

    /**
     * Returns a new UnixTimeUtc object with the hours added
     */
    fun addHours(h: Long): UnixTimeUtc {
        return UnixTimeUtc(milliseconds + (h * 60 * 60 * 1000))
    }

    /**
     * Returns a new UnixTimeUtc object with the days added
     */
    fun addDays(d: Long): UnixTimeUtc {
        return UnixTimeUtc(milliseconds + (d * 24 * 60 * 60 * 1000))
    }

    /**
     * Returns a new UnixTimeUtc object with the milliseconds added
     */
    fun addMilliseconds(ms: Long): UnixTimeUtc {
        return UnixTimeUtc(milliseconds + ms)
    }

    /**
     * Convert to Instant
     */
    fun toInstant(): Instant {
        return Instant.fromEpochMilliseconds(milliseconds)
    }

    /**
     * Check if this time is between start and end
     */
    fun isBetween(start: UnixTimeUtc, end: UnixTimeUtc, inclusive: Boolean = true): Boolean {
        return if (inclusive) {
            this >= start && this <= end
        } else {
            this > start && this < end
        }
    }

    /**
     * Outputs time as ISO 8601 format "yyyy-MM-ddTHH:mm:ssZ"
     */
    fun iso8601(): String {
        return toInstant().toString()
    }

    override fun toString(): String {
        return milliseconds.toString()
    }

    operator fun minus(other: UnixTimeUtc): Duration {
        return (milliseconds - other.milliseconds).milliseconds
    }

    override fun compareTo(other: UnixTimeUtc): Int {
        return milliseconds.compareTo(other.milliseconds)
    }
}
