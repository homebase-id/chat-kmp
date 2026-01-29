package id.homebase.core.auth

import id.homebase.api.browser.RedirectConfig
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.core.AuthPresentationContextProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.Foundation.NSError
import platform.Foundation.NSURL

actual object BrowserLauncher {
    actual fun launchAuthBrowser(
        url: String,
        scope: CoroutineScope,
        onOpenUrl: (String) -> Unit
    ) {
        val session =
            ASWebAuthenticationSession(
                uRL = NSURL.URLWithString(url)!!,
                callbackURLScheme = RedirectConfig.scheme,
                completionHandler = { callbackURL: NSURL?, error: NSError? ->
                    if (callbackURL != null) {
                        val urlString = callbackURL.absoluteString!!
                        scope.launch(Dispatchers.Main) {
                            YouAuthFlowManager.Companion.handleCallback(urlString)
                        }
                    } else if (error != null) {
                        println("Auth error: $error")
                    }
                }
            )
        session.setPresentationContextProvider(AuthPresentationContextProvider())
        session.start()
    }
}