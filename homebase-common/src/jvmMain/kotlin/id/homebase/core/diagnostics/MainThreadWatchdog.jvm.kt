package id.homebase.core.diagnostics

/**
 * Desktop (JVM) stack capture for [MainThreadWatchdog]: snapshots the AWT event-dispatch thread,
 * which is what Compose Desktop's `Dispatchers.Main` (the Swing dispatcher) runs on. A stall here
 * is the desktop equivalent of an Android ANR and previously produced no log at all.
 *
 * [Thread.getAllStackTraces] takes a one-shot snapshot of every thread's stack, so we read the
 * EDT's frames without having to interrupt it.
 */
internal actual fun captureMainThreadStackTrace(maxFrames: Int): String? {
    val edt = Thread.getAllStackTraces().entries
        .firstOrNull { it.key.name.startsWith("AWT-EventQueue") }
        ?: return null
    return buildString {
        appendLine("    [thread: ${edt.key.name}]")
        edt.value.take(maxFrames).forEach { appendLine("    at $it") }
    }
}
