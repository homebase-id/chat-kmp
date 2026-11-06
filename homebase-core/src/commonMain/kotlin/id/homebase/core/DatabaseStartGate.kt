package id.homebase.core

import co.touchlab.kermit.Logger
import id.homebase.api.sync.database.DatabaseUnavailableException

/**
 * Opens the database once, then runs the work queued with [whenOpen]. A [DatabaseUnavailableException] (iOS before
 * the first unlock, a locked desktop key store) keeps [tryOpen] shut so the caller can try again later; any other
 * failure propagates. Not thread-safe: main thread only.
 */
class DatabaseStartGate {
    var isOpen = false
        private set

    private val waiting = mutableListOf<() -> Unit>()

    fun whenOpen(block: () -> Unit) {
        if (isOpen) block() else waiting += block
    }

    suspend fun tryOpen(open: suspend () -> Unit): Boolean =
        try {
            this.open(open)
            true
        } catch (e: DatabaseUnavailableException) {
            Logger.w(tag = "DatabaseStartGate", throwable = e) { "Database not readable yet; waiting" }
            false
        }

    /** For a platform that can't wait: an unreadable database throws as it is. */
    suspend fun open(open: suspend () -> Unit) {
        if (isOpen) return
        open()
        isOpen = true
        val pending = waiting.toList()
        waiting.clear()
        pending.forEach { it() }
    }
}
