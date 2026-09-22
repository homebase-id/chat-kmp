package id.homebase.core.permission

/**
 * Registers a handler for the owner-console "Extend Permissions" return URL on
 * platforms where shared code can't see it: the iOS deep link and the web opener
 * message. Android parses the intent in MainActivity, and desktop intercepts it
 * via the local callback server. The `canceled` flag mirrors the `status=canceled`
 * query param.
 */
expect fun registerPermissionCallbackHandler(handler: (canceled: Boolean) -> Unit)

/** Clears the permission-callback handler registration. */
expect fun unregisterPermissionCallbackHandler()
