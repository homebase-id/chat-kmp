package id.homebase.core.util

import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.toArgb
import co.touchlab.kermit.Logger
import id.homebase.resources.MR
import id.homebase.resources.chat_view_once_capture_blocked
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.darwin.NSObject
import org.jetbrains.compose.resources.stringResource
import platform.CoreGraphics.CGRect
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSStringFromClass
import platform.UIKit.NSTextAlignmentCenter
import platform.UIKit.UIColor
import platform.UIKit.setAccessibilityElementsHidden
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
    val background = MaterialTheme.colorScheme.surface.toArgb()
    val foreground = MaterialTheme.colorScheme.onSurface.toArgb()
    DisposableEffect(active, blockedLabel, background, foreground) {
        if (active) SecureLayer.acquire(blockedLabel, background, foreground)
        onDispose { if (active) SecureLayer.release() }
    }
}

// this::class would resolve a private UIKit class to its nearest Kotlin-known superclass, hiding "CanvasView".
@OptIn(BetaInteropApi::class)
private fun NSObject.className(): String = `class`()?.let { NSStringFromClass(it) }.orEmpty()

private fun Int.toUIColor(): UIColor = UIColor(
    red = ((this shr 16) and 0xFF) / 255.0,
    green = ((this shr 8) and 0xFF) / 255.0,
    blue = (this and 0xFF) / 255.0,
    alpha = ((this ushr 24) and 0xFF) / 255.0,
)

private val secureLog = Logger.withTag("SecureContent")

// Hosts the top view controller's view as a subview of a secure UITextField's canvas view, so captures show the placeholder
// pinned beneath it. A subview (not a re-parented layer) keeps UIKit hit-testing intact. Private UIKit structure: when the
// canvas isn't found nothing is touched and the observer fallback applies.
@OptIn(ExperimentalForeignApi::class)
private object SecureLayer {
    private class Installed(
        val host: UIView,
        val superview: UIView,
        val originalIndex: Long,
        val originalMask: ULong,
        val field: UITextField,
        val placeholder: UIView,
    )

    private var installed: Installed? = null
    private val refCounter = SecureFlagRefCounter()

    // Main thread only: Compose effects are already serialised there.
    fun acquire(label: String, background: Int, foreground: Int) {
        if (refCounter.acquire(flagAlreadySet = false)) installed = install(label, background, foreground)
    }

    fun release() {
        if (!refCounter.release()) return
        installed?.let(::uninstall)
        installed = null
    }

    private fun install(label: String, background: Int, foreground: Int): Installed? {
        val host = topViewController()?.view
        val superview = host?.superview
        if (host == null || superview == null) {
            secureLog.w { "secure layer NOT used (fallback to capture blanking + screenshot signal): no host view" }
            return null
        }
        val flexible = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        val field = UITextField().apply {
            secureTextEntry = true
            setIsAccessibilityElement(false)
            setFrame(superview.bounds)
            setAutoresizingMask(flexible)
        }
        superview.addSubview(field)
        field.layoutIfNeeded()
        val canvas = field.subviews.filterIsInstance<UIView>().firstOrNull { it.className().contains("CanvasView") }
        if (canvas == null) {
            secureLog.w {
                "secure layer NOT used (fallback to capture blanking + screenshot signal): no canvas under UITextField, " +
                    "subviews=${field.subviews.map { (it as UIView).className() }}"
            }
            field.removeFromSuperview()
            return null
        }
        val originalIndex = superview.subviews.indexOf(host).toLong()
        val originalMask = host.autoresizingMask
        val placeholder = placeholderView(label, superview.bounds, background, foreground)
        superview.insertSubview(placeholder, belowSubview = host)
        canvas.setUserInteractionEnabled(true)
        canvas.setFrame(field.bounds)
        canvas.setAutoresizingMask(flexible)
        canvas.addSubview(host)
        host.setFrame(canvas.bounds)
        host.setAutoresizingMask(flexible)
        secureLog.i { "secure layer path: host ${host.className()} moved into ${canvas.className()}" }
        return Installed(host, superview, originalIndex, originalMask, field, placeholder)
    }

    private fun uninstall(hold: Installed) {
        hold.host.removeFromSuperview()
        hold.host.setAutoresizingMask(hold.originalMask)
        hold.host.setFrame(hold.superview.bounds)
        hold.superview.insertSubview(hold.host, atIndex = hold.originalIndex)
        hold.field.removeFromSuperview()
        hold.placeholder.removeFromSuperview()
        secureLog.i { "secure layer path: host restored" }
    }

    private fun placeholderView(label: String, bounds: CValue<CGRect>, background: Int, foreground: Int): UIView {
        val view = UIView(frame = bounds).apply {
            backgroundColor = background.toUIColor()
            userInteractionEnabled = false
            setAccessibilityElementsHidden(true)
            autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        }
        val text = UILabel(frame = bounds).apply {
            this.text = label
            textColor = foreground.toUIColor()
            textAlignment = NSTextAlignmentCenter
            numberOfLines = 0
            setIsAccessibilityElement(false)
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
