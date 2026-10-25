package id.homebase.chat.services.calling

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Runs [drain] on one caller at a time. A caller that arrives while another is draining returns at once and its [request]
 * is served by another [drain] run on the first caller, after the first run returns, so [drain] always sees the latest state.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class SerialDrain(private val drain: () -> Unit) {
    private val pending = AtomicInt(0)

    fun request() {
        if (pending.addAndFetch(1) != 1) return
        try {
            do {
                drain()
            } while (pending.addAndFetch(-1) > 0)
        } catch (t: Throwable) {
            pending.store(0)
            throw t
        }
    }
}
