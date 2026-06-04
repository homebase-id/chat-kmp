package id.homebase.core.ui.auth

// Non-web platforms open auth in Custom Tabs / ASWebAuthenticationSession / the system browser,
// so there is no popup to manage here.
actual fun prepareWebAuthPopup() {}

actual fun closeWebAuthPopup() {}
