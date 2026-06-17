package id.homebase.core.location

/**
 * Basemap rendered under location traces. [None] keeps the fully-offline Canvas
 * trace; [OpenStreetMap] fetches OSM tiles (the viewed area becomes visible to
 * their servers — disclosed next to the selector). Google/Apple are reserved for
 * a later change; only [None] and [OpenStreetMap] are surfaced and implemented today.
 *
 * Persisted by [name] (see LocationPreferences), so adding entries or reordering
 * them never corrupts a stored choice.
 */
enum class LocationMapProvider {
    None,
    OpenStreetMap,
    ;

    companion object {
        val DEFAULT = OpenStreetMap

        fun fromName(name: String?): LocationMapProvider =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
