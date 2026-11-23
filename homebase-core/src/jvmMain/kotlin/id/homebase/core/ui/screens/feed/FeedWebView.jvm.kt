package id.homebase.core.ui.screens.feed

import io.github.kdroidfilter.webview.web.NativeWebView

internal actual fun configureNativeWebView(view: NativeWebView, backgroundArgb: Int) {
    view.background = java.awt.Color(backgroundArgb, true)
}
