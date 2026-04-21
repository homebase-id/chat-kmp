package id.homebase.api.sync.database

interface DatabaseSizeProbe {
    fun sizeBytes(): Long
}
