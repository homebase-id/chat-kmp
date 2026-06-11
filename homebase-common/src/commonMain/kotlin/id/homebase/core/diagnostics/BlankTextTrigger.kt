package id.homebase.core.diagnostics

/** Implemented by the iOS layer (Swift) to surface the blank-text auto-trigger as a visible toast. */
fun interface BlankTextTriggerListener {
    fun onTriggerFired(usedBytes: Long, peakBytes: Long)
}

/**
 * Bridge for the iOS blank-text auto-trigger (see `IosGpuTextDiagnostics`).
 *
 * Trigger HYPOTHESIS: on foreground, the skiko font cache is near-empty (`usedBytes` tiny) although
 * it was substantial earlier this session (`peakBytes` large) — i.e. iOS reclaimed the app's
 * GPU/font resources while backgrounded, the condition that precedes the blank-text bug. We can't
 * actually *see* the blank (the cache rebuilds even when blank, the Metal surface looks healthy), so
 * this is a proxy that needs field validation.
 *
 * This is **observe-only**: when the trigger fires we show a VISIBLE toast (native SwiftUI, so it
 * renders even while Compose text is blank) plus a log — but we do NOT attempt recovery yet. The
 * toast is the instrument: in TestFlight a tester reports whether the screen was actually blank when
 * it appeared (true positive) or fine (false positive). Without it we can't measure the
 * false-positive rate, and we won't justify auto-firing a recovery off this signal until we have.
 *
 * No-op on platforms with no registered [BlankTextTriggerListener] (the bug is iOS-only).
 */
object BlankTextTrigger {
    private var listener: BlankTextTriggerListener? = null

    fun register(listener: BlankTextTriggerListener) {
        this.listener = listener
    }

    fun fire(usedBytes: Long, peakBytes: Long) {
        listener?.onTriggerFired(usedBytes, peakBytes)
    }
}
