package id.homebase.api.browser

import kotlinx.coroutines.CoroutineScope

actual object BrowserLauncher {
    actual fun launchAuthBrowser(url: String, scope: CoroutineScope) {
    }

    actual fun openUrl(url: String) {
    }
}