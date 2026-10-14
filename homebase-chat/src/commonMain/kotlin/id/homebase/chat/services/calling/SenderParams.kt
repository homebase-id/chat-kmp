package id.homebase.chat.services.calling

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update

/** The encoding fields the call writes to its video sender; null leaves the sender's own value. */
internal data class SenderState(
    val maxBitrateBps: Int? = null,
    val minBitrateBps: Int? = null,
    val maxFramerate: Int? = null,
    val scaleDownBy: Double? = null,
)

/** The one writer of the video sender's parameters: they are read-modified-written whole, so two threads would drop each other's fields. */
@OptIn(ExperimentalAtomicApi::class)
internal class SenderParams(private val write: (SenderState) -> Unit) {
    private val state = AtomicReference(SenderState())
    private val drain = SerialDrain { write(state.load()) }

    fun update(change: SenderState.() -> SenderState) {
        state.update { it.change() }
        drain.request()
    }
}
