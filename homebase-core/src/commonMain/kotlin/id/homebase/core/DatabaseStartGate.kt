package id.homebase.core

import co.touchlab.kermit.Logger
import id.homebase.api.sync.database.DatabaseUnavailableException

/**
 * Opens the database once, then runs the work queued with [whenOpen]. A
 * [DatabaseUnavailableException] (iOS before the first unlock) keeps the gate shut so the caller
 * can call [tryOpen] again later; any other failure propagates. Not thread-safe: main thread only.
 */
class DatabaseStartGate(private val open: () -> Unit) {
    var isOpen = false
        private set

    private val waiting = mutableListOf<() -> Unit>()

    fun whenOpen(block: () -> Unit) {
        if (isOpen) block() else waiting += block
    }

    fun tryOpen(): Boolean {
        if (isOpen) return true
        try {
            open()
        } catch (e: DatabaseUnavailableException) {
            Logger.w(tag = "DatabaseStartGate", throwable = e) { "Database not readable yet; waiting" }
            return false
        }
        isOpen = true
        val pending = waiting.toList()
        waiting.clear()
        pending.forEach { it() }
        return true
    }
}
