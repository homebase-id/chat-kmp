inline fun <T, K, V> Iterable<T>.associateNotNull(
    transform: (T) -> Pair<K, V>?
): Map<K, V> {
    val map = mutableMapOf<K, V>()
    for (item in this) {
        val pair = transform(item)
        if (pair != null) {
            map[pair.first] = pair.second
        }
    }
    return map
}