package id.homebase.core.util

// iOS-only. Web opens URLs via rememberAuthBrowserLauncher (window.open) in the UI layer.
actual object InAppBrowser {
    actual fun open(url: String) {}
}
