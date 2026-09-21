@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.UIKit.UIColor
import platform.UIKit.UIScrollViewContentInsetAdjustmentBehavior
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKNavigationTypeOther
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

private const val MESSAGE_HANDLER = "homebaseCard"

actual fun createCardHost(): CardHost = IosCardHost()

@Composable
actual fun CardHostView(host: CardHost, modifier: Modifier) {
    val cardHost = host as IosCardHost
    UIKitView(
        factory = { cardHost.webView },
        modifier = modifier,
    )
}

internal class IosCardHost : CardHostBase() {
    private val messageHandler = MessageHandler { body -> (body as? String)?.let(::onBridgeMessage) }
    private val navigationPolicy = NavigationPolicy(
        onBlocked = { url -> onPageError("blocked navigation to $url") },
        onProcessTerminated = {
            onPageError("web content process terminated")
            loadPage()
        },
    )

    val webView = WKWebView(
        frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
        configuration = WKWebViewConfiguration().apply {
            userContentController.addUserScript(
                WKUserScript(
                    source = bridgeShim("window.webkit.messageHandlers.$MESSAGE_HANDLER.postMessage"),
                    injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                    forMainFrameOnly = true,
                ),
            )
            userContentController.addScriptMessageHandler(messageHandler, MESSAGE_HANDLER)
        },
    ).apply {
        setOpaque(false)
        setBackgroundColor(UIColor.clearColor)
        scrollView.setBackgroundColor(UIColor.clearColor)
        scrollView.contentInsetAdjustmentBehavior =
            UIScrollViewContentInsetAdjustmentBehavior.UIScrollViewContentInsetAdjustmentNever
        navigationDelegate = navigationPolicy
    }

    init {
        loadPage()
    }

    override fun loadHtml(html: String) {
        webView.loadHTMLString(html, NSURL(string = CARD_BASE_URL))
    }

    override fun evaluateNow(js: String) {
        webView.evaluateJavaScript(js, null)
    }

    override fun release() {
        // The content controller retains the handler, which retains this host.
        webView.configuration.userContentController.removeScriptMessageHandlerForName(MESSAGE_HANDLER)
        webView.navigationDelegate = null
        webView.stopLoading()
        webView.removeFromSuperview()
    }
}

private class MessageHandler(private val onMessage: (Any?) -> Unit) : NSObject(), WKScriptMessageHandlerProtocol {
    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        onMessage(didReceiveScriptMessage.body)
    }
}

private class NavigationPolicy(
    private val onBlocked: (String?) -> Unit,
    private val onProcessTerminated: () -> Unit,
) : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val url = decidePolicyForNavigationAction.request.URL?.absoluteString
        val isInitialLoad = decidePolicyForNavigationAction.navigationType == WKNavigationTypeOther &&
            (url == CARD_BASE_URL || url == "about:blank")
        if (isInitialLoad) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
        } else {
            onBlocked(url)
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
        }
    }

    override fun webViewWebContentProcessDidTerminate(webView: WKWebView) {
        onProcessTerminated()
    }
}
