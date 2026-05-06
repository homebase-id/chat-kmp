package id.homebase.chat.location

/** A single GPS fix used for the one-shot "share my current location" flow. */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
)
