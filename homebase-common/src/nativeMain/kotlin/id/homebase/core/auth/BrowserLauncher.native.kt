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

/**
 * iOS implementation of BrowserLauncher.
 *
 * iOS uses ASWebAuthenticationSession which couples browser launching with callback handling. Due
 * to this API constraint, we launch the browser here rather than in the UI layer.
 */
actual object BrowserLauncher {
    actual fun onAuthBrowserOpened(url: String, scope: CoroutineScope) {
        // iOS ASWebAuthenticationSession handles both browser launching AND callback
        // This is an iOS-specific API constraint - we can't separate the two
        val session =
                ASWebAuthenticationSession(
                        uRL = NSURL.URLWithString(url)!!,
                        callbackURLScheme = RedirectConfig.scheme,
                        completionHandler = { callbackURL: NSURL?, error: NSError? ->
                            if (callbackURL != null) {
                                val urlString = callbackURL.absoluteString!!
                                scope.launch(Dispatchers.Main) {
                                    YouAuthFlowManager.handleCallback(urlString)
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
