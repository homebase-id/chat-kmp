package id.homebase.core.logging

/**
 * Turns crash and error reporting to the remote backend on or off for this process; it does not persist the choice, the caller reapplies it at start.
 * Android and iOS toggle Crashlytics (iOS does nothing until the Crashlytics bridge is registered). Desktop and web have no backend and ignore it.
 * Safe to call from any thread.
 */
expect fun setErrorCollectionEnabled(enabled: Boolean)