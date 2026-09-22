package id.homebase.api.platform

/**
 * A short OS-granted window to keep running after the app leaves the foreground.
 *
 * iOS suspends the process about a second after backgrounding, which freezes an in-flight
 * upload POST mid-transfer; its in-process timeout can't fire while suspended, so the
 * outbox row stays checked out until the user foregrounds the app again — observed as
 * 9h stalls for uploads that need 10 seconds (#1467).
 *
 * Ceiling: iOS grants tens of seconds (~30s), not the 5-minute `requestTimeoutMillis` a single
 * POST is allowed. An upload still running when the grant lapses suspends mid-POST exactly as
 * before — the expiry path logs it so the residual case stays distinguishable from the fix.
 * Eliminating that class needs a background NSURLSession, which is its own change.
 *
 * [end] must be called on every path — iOS terminates an app that lets an assertion expire,
 * which is why the iOS actual ends the task from its expiration handler.
 */
interface BackgroundExecutionAssertion {
    fun end()
}

expect fun beginBackgroundExecutionAssertion(name: String): BackgroundExecutionAssertion

/** For platforms that don't suspend a process out from under an in-flight request. */
internal object NoBackgroundExecutionAssertion : BackgroundExecutionAssertion {
    override fun end() = Unit
}
