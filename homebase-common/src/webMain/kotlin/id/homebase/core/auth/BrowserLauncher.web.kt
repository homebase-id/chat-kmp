package id.homebase.core.auth

import kotlinx.coroutines.CoroutineScope

actual object BrowserLauncher {
    actual fun launchAuthBrowser(
        url: String,
        scope: CoroutineScope,
        onOpenUrl: (String) -> Unit
    ) {
        onOpenUrl(url)
    }
}