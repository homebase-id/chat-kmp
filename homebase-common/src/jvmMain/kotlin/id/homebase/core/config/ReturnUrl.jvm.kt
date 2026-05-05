package id.homebase.core.config

import co.touchlab.kermit.Logger
import id.homebase.api.browser.LocalCallbackServer

private const val TAG = "ReturnUrl.jvm"

/**
 * Desktop return URL: localhost loopback served by [LocalCallbackServer].
 *
 * Custom URL schemes (`homebase-fchat://`) are not registered on desktop OSes, so the
 * owner-console redirect needs to land somewhere the desktop process is actually
 * listening. The OAuth login flow uses the same server and the same trick.
 *
 * The server is shared with the OAuth callback. If the OAuth flow already started it,
 * we reuse the live port. If not, we pick an available port and start a server with a
 * no-op auth callback — only the `/permission-callback` and `/focus` routes will be
 * needed for this flow, and the auth callback can be replaced by the login flow later
 * via [LocalCallbackServer.setCallBack].
 */
actual fun returnUrl(): String {
    val port = ensureCallbackServer()
    return "http://localhost:$port/permission-callback"
}

private fun ensureCallbackServer(): Int {
    if (LocalCallbackServer.isRunning()) {
        return LocalCallbackServer.getPort()
    }
    val port = LocalCallbackServer.start(onCallbackUrl = {
        // No-op: this server instance was started for the permission-extend flow.
        // The OAuth login flow will replace this handler via setCallBack() if it
        // launches while we're still running.
        Logger.d(tag = TAG) { "OAuth callback fired on permission-flow server (no-op)" }
    })
    if (port < 0) {
        Logger.e(tag = TAG) { "Failed to start LocalCallbackServer for permission-extend return URL" }
        // Fall back to a deep link — the owner console will at least produce a clean
        // error rather than redirecting to nothing.
        return 0
    }
    return port
}
