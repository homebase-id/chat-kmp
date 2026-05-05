package id.homebase.core.permission

/**
 * Registers a handler for the owner-console "Extend Permissions" return URL
 * (`homebase-fchat://permission-callback?status=...`) on platforms where the
 * deep link can't be parsed in shared code. Only iOS uses this — Android parses
 * the intent in MainActivity, and desktop intercepts it via the local callback
 * server. The `canceled` flag mirrors the `status=canceled` query param.
 */
expect fun registerPermissionCallbackHandler(handler: (canceled: Boolean) -> Unit)

/** Clears the permission-callback handler registration. */
expect fun unregisterPermissionCallbackHandler()
