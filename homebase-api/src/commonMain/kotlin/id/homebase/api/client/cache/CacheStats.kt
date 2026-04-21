package id.homebase.api.client.cache

data class CacheStats(
    val id: String,
    val sizeBytes: Long,
    val maxBytes: Long,
)
