package id.homebase.api.media

/** Where a photo or video was taken, in degrees and metres, as its camera recorded it. */
data class MediaLocation(val latitude: Double, val longitude: Double, val altitude: Double? = null) {
    companion object {
        private val ISO_6709 = Regex("""^([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)?""")

        /** An ISO 6709 point in decimal degrees, "+37.7749-122.4194+010.000/", as cameras write it in a video. */
        fun parse(iso6709: String): MediaLocation? {
            val match = ISO_6709.find(iso6709.trim()) ?: return null
            val (lat, lon, alt) = match.destructured
            val latitude = lat.toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return null
            val longitude = lon.toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return null
            return MediaLocation(latitude, longitude, alt.toDoubleOrNull())
        }
    }
}
