package id.homebase.core.diagnostics

/** Single-threaded browser — no dedicated-thread liveness probe applies. */
internal actual fun installMainThreadLivenessProbe() {
    // no-op
}
