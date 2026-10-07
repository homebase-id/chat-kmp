package id.homebase.core.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import co.touchlab.kermit.Logger
import id.homebase.resources.MR
import id.homebase.resources.chat_view_once_capture_blocked
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.darwin.NSObject
import org.jetbrains.compose.resources.stringResource
import platform.CoreGraphics.CGRect
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSStringFromClass
import platform.QuartzCore.CALayer
import platform.UIKit.NSTextAlignmentCenter
import platform.UIKit.UIColor
import platform.UIKit.setIsAccessibilityElement
import platform.UIKit.UIFont
import platform.UIKit.UIFontTextStyleBody
import platform.UIKit.UILabel
import platform.UIKit.UITextField
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationUserDidTakeScreenshotNotification
import platform.UIKit.UIScreen
import platform.UIKit.UIScreenCapturedDidChangeNotification

@Composable
actual fun SecureWindowEffect(active: Boolean) {
    val blockedLabel = stringResource(MR.string.chat_view_once_capture_blocked)
    DisposableEffect(active, blockedLabel) {
        val hold = if (active) SecureLayer.acquire(blockedLabel) else null
        onDispose { hold?.let(SecureLayer::release) }
    }
}

private fun NSObject.className(): String = `class`()?.let { NSStringFromClass(it) }.orEmpty()

private val secureLog = Logger.withTag("SecureContent")

// Hosts the top view controller's view inside a secure UITextField's canvas layer, so captures show the placeholder
// pinned beneath it. Private UIKit structure: when the canvas isn't found nothing is touched and the observer fallback applies.
@OptIn(ExperimentalForeignApi::class)
private object SecureLayer {
    private class Installed(
        val host: UIView,
        val parentLayer: CALayer,
        val originalIndex: ULong,
        val field: UITextField,
        val placeholder: UIView,
    )

    private var installed: Installed? = null
    private var holds = 0

    // Main thread only: Compose effects are already serialised there.
    fun acquire(label: String): Any? {
        if (holds++ == 0) installed = install(label)
        return if (installed != null) this else null.also { holds-- }
    }

    fun release(token: Any) {
        if (holds == 0 || --holds > 0) return
        installed?.let(::uninstall)
        installed = null
    }

    private fun install(label: String): Installed? {
        val host = topViewController()?.view
        val superview = host?.superview
        val parentLayer = host?.layer?.superlayer
        if (host == null || superview == null || parentLayer == null) {
            secureLog.w { "secure layer NOT used (fallback to capture blanking + screenshot signal): no host view" }
            return null
        }
        val field = UITextField().apply {
            secureTextEntry = true
            userInteractionEnabled = false
            setIsAccessibilityElement(false)
            setFrame(superview.bounds)
            setAutoresizingMask(UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight)
        }
        superview.addSubview(field)
        field.layoutIfNeeded()
        val canvas = field.subviews.filterIsInstance<UIView>().firstOrNull { it.className().contains("CanvasView") }?.layer
        if (canvas == null) {
            secureLog.w {
                "secure layer NOT used (fallback to capture blanking + screenshot signal): no canvas under UITextField, " +
                    "subviews=${field.subviews.map { (it as UIView).className() }}"
            }
            field.removeFromSuperview()
            return null
        }
        val originalIndex = (parentLayer.sublayers?.indexOf(host.layer) ?: -1).takeIf { it >= 0 }?.toULong()
        if (originalIndex == null) {
            field.removeFromSuperview()
            secureLog.w { "secure layer NOT used (fallback to capture blanking + screenshot signal): host layer not in parent" }
            return null
        }
        val placeholder = placeholderView(label, superview.bounds)
        superview.insertSubview(placeholder, belowSubview = host)
        canvas.addSublayer(host.layer)
        secureLog.i { "secure layer path: host ${host.className()} moved into ${canvas.className()}" }
        return Installed(host, parentLayer, originalIndex, field, placeholder)
    }

    private fun uninstall(hold: Installed) {
        hold.host.layer.removeFromSuperlayer()
        hold.field.removeFromSuperview()
        hold.placeholder.removeFromSuperview()
        hold.parentLayer.insertSublayer(hold.host.layer, atIndex = hold.originalIndex.toUInt())
        secureLog.i { "secure layer path: host restored" }
    }

    private fun placeholderView(label: String, bounds: CValue<CGRect>): UIView {
        val view = UIView(frame = bounds).apply {
            backgroundColor = UIColor.blackColor
            userInteractionEnabled = false
            autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        }
        val text = UILabel(frame = bounds).apply {
            this.text = label
            textColor = UIColor.whiteColor
            textAlignment = NSTextAlignmentCenter
            numberOfLines = 0
            font = UIFont.preferredFontForTextStyle(UIFontTextStyleBody)
            autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        }
        view.addSubview(text)
        return view
    }
}

@Composable
actual fun rememberScreenCaptureObserver(onScreenshot: () -> Unit): State<Boolean> {
    val captured = remember { mutableStateOf(UIScreen.mainScreen.captured) }
    val latestOnScreenshot by rememberUpdatedState(onScreenshot)
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val captureObserver = center.addObserverForName(
            name = UIScreenCapturedDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> captured.value = UIScreen.mainScreen.captured }
        val shotObserver = center.addObserverForName(
            name = UIApplicationUserDidTakeScreenshotNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> latestOnScreenshot() }
        captured.value = UIScreen.mainScreen.captured
        onDispose {
            center.removeObserver(captureObserver)
            center.removeObserver(shotObserver)
        }
    }
    return captured
}
