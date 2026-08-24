package id.homebase.core.util

actual object InAppBrowser {
    // Desktop has no in-app browser and needs none: the system browser is a separate window, so
    // the app stays reachable behind it. Clipboard fallback covers the Linux setups where BROWSE
    // is unsupported (see BrowserUtils).
    actual fun open(url: String) {
        BrowserUtils.openSystemBrowser(url, enableClipboardFallback = true)
    }
}
