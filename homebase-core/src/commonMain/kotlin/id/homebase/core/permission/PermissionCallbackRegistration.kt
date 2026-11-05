package id.homebase.core.permission

import id.homebase.api.youauth.PermissionCallback

/**
 * Registers a handler for the owner-console "Extend Permissions" return URL on
 * platforms where shared code can't see it: the iOS deep link and the web opener
 * message. Android parses the intent in MainActivity, and desktop intercepts it
 * via the local callback server. Each platform hands over the URL as it came, and [PermissionCallback.parse]
 * reads it, so all four read it the same way.
 */
expect fun registerPermissionCallbackHandler(handler: (PermissionCallback) -> Unit)

/** Clears the permission-callback handler registration. */
expect fun unregisterPermissionCallbackHandler()
