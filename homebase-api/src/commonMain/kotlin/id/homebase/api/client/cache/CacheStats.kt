package id.homebase.api.client.cache

data class CacheStats(
    val id: String,
    val sizeBytes: Long,
    val maxBytes: Long,
) {
    companion object {
        // Sentinel for [sizeBytes] when a cache is unhealthy and cannot be
        // queried — see the per-cache try/catch in each provider's
        // getCacheStats(). The Storage screen renders this as a red
        // "Unavailable" row and surfaces a banner pointing the user at
        // "Clear caches", which wipes the directory and resets the ctor
        // tombstone so the next access gets a fresh FileKache.
        const val UNAVAILABLE: Long = -1L
    }
}
