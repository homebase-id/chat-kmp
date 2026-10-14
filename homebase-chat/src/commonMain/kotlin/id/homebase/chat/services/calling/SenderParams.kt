package id.homebase.chat.services.calling

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** The encoding fields the call writes to its video sender; null leaves the sender's own value. */
internal data class SenderState(
    val maxBitrateBps: Int? = null,
    val minBitrateBps: Int? = null,
    val maxFramerate: Int? = null,
    val scaleDownBy: Double? = null,
)

/**
 * The one owner of the video sender's parameters. A sender's parameters are read, changed and written back whole, so two
 * writers on different threads could overwrite each other's fields. Each [update] changes the desired state, and whichever
 * caller is first in writes the full state; callers that arrive meanwhile have their change written by that same caller.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class SenderParams(private val write: (SenderState) -> Unit) {
    private val state = AtomicReference(SenderState())
    private val drain = SerialDrain { write(state.load()) }

    fun update(change: SenderState.() -> SenderState) {
        while (true) {
            val before = state.load()
            if (state.compareAndSet(before, before.change())) break
        }
        drain.request()
    }
}
