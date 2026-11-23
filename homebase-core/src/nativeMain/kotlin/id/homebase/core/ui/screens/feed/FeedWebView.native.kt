package id.homebase.core.ui.screens.feed

import io.github.kdroidfilter.webview.web.NativeWebView
import platform.UIKit.UIColor

internal actual fun configureNativeWebView(view: NativeWebView, backgroundArgb: Int) {
    val color = UIColor(
        red = ((backgroundArgb shr 16) and 0xFF) / 255.0,
        green = ((backgroundArgb shr 8) and 0xFF) / 255.0,
        blue = (backgroundArgb and 0xFF) / 255.0,
        alpha = 1.0,
    )
    view.opaque = false
    view.backgroundColor = color
    view.scrollView.backgroundColor = color
}
