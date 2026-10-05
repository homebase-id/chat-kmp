package id.homebase.core.diagnostics

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// The browser runs on a single (main) thread, so a watchdog can't capture "another thread's"
// stack the way the JVM/native actuals do — there is nothing to render. Returning null matches
// the expect's contract for platforms that can't capture a foreign thread's stack.
actual fun captureMainThreadStackTrace(maxFrames: Int): String? = null

internal actual fun captureProcessTimes(): ProcessTimes? = null

internal actual fun createWatchdogDispatcher(): CoroutineDispatcher = Dispatchers.Default

internal actual fun currentThreadName(): String = "main"
