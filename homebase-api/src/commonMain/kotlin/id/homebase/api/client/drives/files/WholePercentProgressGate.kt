package id.homebase.api.client.drives.files

/**
 * Collapses a stream of `0..100` progress fractions down to at most one value per whole
 * integer percent.
 *
 * The HTTP upload client invokes its `onProgress` callback once per network chunk, so a
 * multi-MB upload would otherwise emit hundreds of [id.homebase.api.client.eventbus.BackendEvent.OutboxEvent.ItemProgress]
 * events — most carrying the same integer percent the UI rounds to — via the suspending
 * `EventBus.emit`, overflowing the bus buffer and parking the producer on backpressure.
 * Gating the callback through [admit] guarantees at most 101 emits (0..100 inclusive) per
 * upload and never two in a row for the same whole percent.
 *
 * **Not thread-safe.** Create one gate per upload and call [admit] only from that upload's
 * progress callback (which the HTTP client invokes sequentially). Concurrent uploads each
 * get their own gate, so there is no shared mutable state to race on.
 */
internal class WholePercentProgressGate {
    private var lastWhole = -1

    /**
     * @return [percent] when its whole-number part differs from the last admitted value
     *   (so the caller should emit it), or `null` when it falls in the same integer percent
     *   as the previous admitted value (so the caller should skip it). The very first call
     *   is always admitted, including at `0`.
     */
    fun admit(percent: Float): Float? {
        val whole = percent.toInt()
        if (whole == lastWhole) return null
        lastWhole = whole
        return percent
    }
}

/** [report] sees the 0..1 fractions passed in, at most once per whole percent. */
fun wholePercentFractions(report: (Float) -> Unit): (Float) -> Unit {
    val gate = WholePercentProgressGate()
    return { fraction -> if (gate.admit(fraction * 100) != null) report(fraction) }
}

/** Sent/total byte counts as 0..1 fractions for [report], at most once per whole percent; nothing while the total is unknown. */
fun wholePercentBytes(report: (Float) -> Unit): suspend (Long, Long?) -> Unit {
    val fractions = wholePercentFractions(report)
    return { sent, total -> if (total != null && total > 0) fractions((sent.toFloat() / total).coerceAtMost(1f)) }
}
