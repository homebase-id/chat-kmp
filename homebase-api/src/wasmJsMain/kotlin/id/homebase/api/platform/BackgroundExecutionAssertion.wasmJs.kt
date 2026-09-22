package id.homebase.api.platform

// Nothing to assert: the host never suspends the process out from under a request.
actual fun beginBackgroundExecutionAssertion(name: String): BackgroundExecutionAssertion =
    NoBackgroundExecutionAssertion
