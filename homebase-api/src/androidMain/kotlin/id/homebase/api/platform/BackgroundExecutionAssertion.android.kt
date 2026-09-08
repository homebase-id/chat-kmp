package id.homebase.api.platform

// Android doesn't suspend a backgrounded process mid-transfer the way iOS does — a real
// assertion here means a dataSync foreground service, which is its own change (#1467).
actual fun beginBackgroundExecutionAssertion(name: String): BackgroundExecutionAssertion =
    NoBackgroundExecutionAssertion
