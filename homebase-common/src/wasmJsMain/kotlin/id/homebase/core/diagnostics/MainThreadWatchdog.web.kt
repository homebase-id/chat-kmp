package id.homebase.core.diagnostics

// The browser runs on a single (main) thread, so a watchdog can't capture "another thread's"
// stack the way the JVM/native actuals do — there is nothing to render. Returning null matches
// the expect's contract for platforms that can't capture a foreign thread's stack.
internal actual fun captureMainThreadStackTrace(maxFrames: Int): String? = null
