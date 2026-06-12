package id.homebase.core.location.tracking

/**
 * Observe whole-app foreground/background transitions (process-level, not
 * per-screen). [onChange] receives `true` when the app becomes foreground.
 * Fires the current state once on registration. Call from the main thread at
 * process start; the registration lives for the process lifetime.
 */
expect fun observeAppForeground(onChange: (Boolean) -> Unit)
