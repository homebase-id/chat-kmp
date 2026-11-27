package id.homebase.api.media

/** Where a photo or video was taken, in degrees and metres, as its camera recorded it. */
data class MediaLocation(val latitude: Double, val longitude: Double, val altitude: Double? = null) {
    /** The point as [parse] reads it, to four decimals: "+59.9139+010.7522/". */
    fun toIso6709(): String = buildString {
        append(coordinate(latitude, 2))
        append(coordinate(longitude, 3))
        altitude?.let { append(coordinate(it, 1)) }
        append('/')
    }

    private fun coordinate(value: Double, wholeDigits: Int): String {
        val tenThousandths = kotlin.math.round(kotlin.math.abs(value) * 10_000).toLong()
        val whole = (tenThousandths / 10_000).toString().padStart(wholeDigits, '0')
        val fraction = (tenThousandths % 10_000).toString().padStart(4, '0')
        return (if (value < 0 && tenThousandths != 0L) "-" else "+") + whole + "." + fraction
    }

    companion object {
        private val ISO_6709 = Regex("""^([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)?""")

        /** An ISO 6709 point in decimal degrees, "+37.7749-122.4194+010.000/", as cameras write it in a video. */
        fun parse(iso6709: String): MediaLocation? {
            val match = ISO_6709.find(iso6709.trim()) ?: return null
            val (lat, lon, alt) = match.destructured
            return of(lat.toDoubleOrNull(), lon.toDoubleOrNull(), alt.toDoubleOrNull())
        }

        /** Null unless [latitude] and [longitude] are a point on Earth. */
        fun of(latitude: Double?, longitude: Double?, altitude: Double? = null): MediaLocation? {
            if (latitude == null || latitude !in -90.0..90.0 || longitude == null || longitude !in -180.0..180.0) return null
            return MediaLocation(latitude, longitude, altitude)
        }
    }
}
