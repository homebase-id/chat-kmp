package id.homebase.api.util

/** Accepts the first [limit] occurrences of a key and rejects every one after that. */
internal class RepeatedErrorFilter(private val limit: Int = 3) {
    private val counts = mutableMapOf<String, Int>()

    fun accept(key: String): Boolean {
        val seen = (counts[key] ?: 0) + 1
        counts[key] = seen
        return seen <= limit
    }
}
