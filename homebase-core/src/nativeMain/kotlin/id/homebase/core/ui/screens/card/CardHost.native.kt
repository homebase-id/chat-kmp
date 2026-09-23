@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.viewinterop.UIKitView
import id.homebase.core.image.NativeImageDecoder
import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UIScrollViewContentInsetAdjustmentBehavior
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKNavigationResponse
import platform.WebKit.WKNavigationResponsePolicy
import platform.WebKit.WKNavigationTypeOther
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKSnapshotConfiguration
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

private const val MESSAGE_HANDLER = "homebaseCard"
private const val COVER_DOWNSCALE = 2.0

actual fun createCardHost(odinId: String): CardHost = IosCardHost(cardPageUrl(odinId))

@Composable
actual fun CardHostView(host: CardHost, modifier: Modifier) {
    val cardHost = host as IosCardHost
    UIKitView(
        factory = { cardHost.webView },
        modifier = modifier,
    )
}

internal class IosCardHost(pageUrl: String) : CardHostBase(pageUrl) {
    private val messageHandler = MessageHandler { body -> (body as? String)?.let(::onBridgeMessage) }
    private val navigationPolicy = NavigationPolicy(
        pageUrl = pageUrl,
        onBlocked = { url -> onPageError("blocked navigation to $url") },
        onFinished = ::onMainFrameFinished,
        onFailed = ::onMainFrameFailed,
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

    override fun loadUrl(url: String) {
        webView.loadRequest(NSURLRequest(uRL = NSURL(string = url)))
    }

    override fun send(command: CardCommand) {
        webView.evaluateJavaScript(command.script(), null)
    }

    override suspend fun snapshot(): ImageBitmap? {
        if (webView.window == null) return null
        val width = webView.bounds.useContents { size.width }
        if (width <= 0.0) return null
        val configuration = WKSnapshotConfiguration().apply { snapshotWidth = NSNumber(double = width / COVER_DOWNSCALE) }
        val image = suspendCancellableCoroutine<UIImage?> { continuation ->
            webView.takeSnapshotWithConfiguration(configuration) { snapshot, _ -> continuation.resume(snapshot) }
        } ?: return null
        return withContext(Dispatchers.Default) { NativeImageDecoder.decodeUIImage(image)?.toComposeImageBitmap() }
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
    private val pageUrl: String,
    private val onBlocked: (String?) -> Unit,
    private val onFinished: () -> Unit,
    private val onFailed: (message: String, unsupported: Boolean) -> Unit,
    private val onProcessTerminated: () -> Unit,
) : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val action = decidePolicyForNavigationAction
        val url = action.request.URL?.absoluteString
        val isPageLoad = action.targetFrame?.mainFrame == true &&
            action.navigationType == WKNavigationTypeOther &&
            url == pageUrl
        if (isPageLoad) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
        } else {
            onBlocked(url)
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
        }
    }

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationResponse: WKNavigationResponse,
        decisionHandler: (WKNavigationResponsePolicy) -> Unit,
    ) {
        val response = decidePolicyForNavigationResponse
        val status = (response.response as? NSHTTPURLResponse)?.statusCode ?: 0L
        if (response.forMainFrame && status >= 400) {
            onFailed("HTTP $status loading ${response.response.URL?.absoluteString}", true)
            decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyCancel)
        } else {
            decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyAllow)
        }
    }

    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) = onFinished()

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) =
        onFailed(withError.describe(), false)

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) =
        onFailed(withError.describe(), false)

    override fun webViewWebContentProcessDidTerminate(webView: WKWebView) {
        onProcessTerminated()
    }

    private fun NSError.describe() = "$localizedDescription ($domain $code)"
}
