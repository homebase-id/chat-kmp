package id.homebase.core.util

// iOS-only. Android opens URLs in-app via rememberAuthBrowserLauncher (Custom Tabs) in the UI layer.
actual object InAppBrowser {
    actual fun open(url: String) {}
}
