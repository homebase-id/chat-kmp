package id.homebase.app

import kotlinx.browser.document
import kotlinx.browser.sessionStorage
import kotlinx.browser.window

/**
 * Seamless owner-session login for the web app (issue #853).
 *
 * When the wasm bundle is hosted on the identity's own domain (production serves it under
 * `/apps/chat-wasm/`), the owner console's session cookie lives on the same origin. Instead of
 * showing the login form + YouAuth popup, we navigate the top window straight to the authorize
 * endpoint: the cookie rides along, odin-core skips consent for same-origin first-party apps
 * (`YouAuthUnifiedService.NeedConsent` — "Apps on /owner doesn't need consent"), and the user
 * lands back here with the callback params, already authorized. This mirrors the JS chat app's
 * `AutoAuthorize` component in odin-js.
 *
 * The redirect unloads the app, so [YouAuthFlowManager.authorize] is called with
 * `persistForRedirect = true` to keep the ECDH key material available for the finalize step
 * after reboot.
 */
object SeamlessOwnerLogin {
    /**
     * One-shot-per-tab guard against redirect loops: a finalize error routes back to the auth
     * screen, which must NOT immediately re-redirect (odin-core has a known redirect-loop
     * wrinkle in this flow — see the TODO in `YouAuthUnifiedService.NeedConsent`). The flag
     * also, deliberately, suppresses auto-relogin after an explicit logout in the same tab:
     * the user asked to be logged out; silently signing them back in would be hostile.
     */
    private const val ATTEMPTED_FLAG = "youauth_seamless_attempted"

    private const val CALLBACK_PATH_SEGMENT = "authorization-code-callback"

    /**
     * The app is eligible for seamless login when it's served from an owner-apps path on the
     * identity domain (mirrors odin-js's `pathname.startsWith(OWNER_APPS_ROOT)` check). The
     * dev server (localhost, root path) and any external hosting fall through to the normal
     * popup flow.
     */
    fun isEligible(): Boolean = window.location.pathname.startsWith("/apps/")

    /** The identity is implicit in the origin — no manual Homebase-ID entry. */
    fun identityFromOrigin(): String = window.location.host

    /**
     * If the current URL is a top-level YouAuth callback (redirect flow — the popup flow's
     * callback never boots the app; index.html intercepts it via `window.opener` and closes),
     * capture the full URL for [YouAuthFlowManager.handleCallback] and clean the address bar
     * back to the app's base so the SPA router never sees the callback path. Returns null on
     * a normal boot.
     */
    fun consumePendingCallbackUrl(): String? {
        val location = window.location
        if (!location.pathname.contains(CALLBACK_PATH_SEGMENT)) return null
        if (!location.search.contains("state=")) return null
        val href = location.href
        window.history.replaceState(null, "", document.baseURI)
        return href
    }

    /**
     * Mark the seamless attempt for this tab session. Returns false if an attempt was already
     * made (loop guard) — callers must then fall through to the normal login screen.
     */
    fun markAttemptedOnce(): Boolean {
        if (sessionStorage.getItem(ATTEMPTED_FLAG) != null) return false
        sessionStorage.setItem(ATTEMPTED_FLAG, "1")
        return true
    }

    /** Top-level navigation to the authorize URL — unloads the app by design. */
    fun navigateToAuthorize(url: String) {
        window.location.href = url
    }
}
