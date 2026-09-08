package id.homebase.api.platform

/**
 * A short OS-granted window to keep running after the app leaves the foreground.
 *
 * iOS suspends the process about a second after backgrounding, which freezes an in-flight
 * upload POST mid-transfer; its in-process timeout can't fire while suspended, so the
 * outbox row stays checked out until the user foregrounds the app again — observed as
 * 9h stalls for uploads that need 10 seconds (#1467).
 *
 * [end] must be called on every path; iOS terminates an app that lets an assertion expire.
 */
interface BackgroundExecutionAssertion {
    fun end()
}

expect fun beginBackgroundExecutionAssertion(name: String): BackgroundExecutionAssertion

/** For platforms that don't suspend a process out from under an in-flight request. */
internal object NoBackgroundExecutionAssertion : BackgroundExecutionAssertion {
    override fun end() = Unit
}
