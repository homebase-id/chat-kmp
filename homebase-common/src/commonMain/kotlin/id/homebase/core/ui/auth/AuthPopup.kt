package id.homebase.core.ui.auth

/**
 * Web-only auth popup lifecycle. On web, YouAuth runs in a popup that must be opened
 * **synchronously inside the login click gesture** or the browser blocks it — so the popup is
 * opened blank here on click, then navigated to the auth URL once the (async) identity ping and
 * `authorize()` finish (see [rememberAuthBrowserLauncher]).
 *
 * All non-web platforms are no-ops (they use Custom Tabs / ASWebAuthenticationSession / the
 * system browser instead).
 */
expect fun prepareWebAuthPopup()

/** Close a still-open auth popup (e.g. when the login attempt fails before navigating it). */
expect fun closeWebAuthPopup()
