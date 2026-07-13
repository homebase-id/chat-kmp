package id.homebase.core.util

// iOS-only. Desktop opens URLs via rememberAuthBrowserLauncher (system browser) in the UI layer.
actual object InAppBrowser {
    actual fun open(url: String) {}
}
