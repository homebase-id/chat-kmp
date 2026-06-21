package id.homebase.core.ui.screens.location.livelocation

/**
 * A friend you're sharing with (or yourself), reduced to what the live map renders. [key] is stable
 * across store emissions (the OdinId domain, or "self") so each avatar composable instance — and its
 * Coil image load — survives position updates without reloading/flicker.
 */
data class LiveMarker(
    val key: String,
    val lat: Double,
    val lon: Double,
    val avatarUrl: String?,
    val initials: String,
    /** Age of this position (now − receivedAt) in ms; drives the staleness label. */
    val ageMs: Long,
    val isSelf: Boolean = false,
)

data class LiveLocationUiState(
    val markers: List<LiveMarker> = emptyList(),
    val showMapTiles: Boolean = true,
)

/**
 * A live position is shown for up to 30 minutes after receipt; past that it's dropped from the map
 * and the dashboard "Live location sharing" section. Same window for both, by design.
 */
const val LIVE_STALE_MS: Long = 30 * 60 * 1000L

/** A dot older than 2 minutes gets a small "Nm" age label beside it. Fresher dots show none. */
const val AGE_LABEL_AFTER_MS: Long = 2 * 60 * 1000L
