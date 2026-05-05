package id.homebase.app.lifecycle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.FrameWindowScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import co.touchlab.kermit.Logger
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

/**
 * Desktop-specific LifecycleOwner that tracks window focus to manage lifecycle states.
 * On Desktop, the window gaining focus is equivalent to RESUMED, and losing focus is STARTED.
 */
class DesktopLifecycleOwner : LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    init {
        // Start in CREATED state
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onWindowVisible() {
        Logger.d(tag = "DesktopLifecycleOwner") { "onWindowVisible -> STARTED" }
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    fun onWindowFocusGained() {
        Logger.d(tag = "DesktopLifecycleOwner") { "onWindowFocusGained -> RESUMED" }
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onWindowFocusLost() {
        Logger.d(tag = "DesktopLifecycleOwner") { "onWindowFocusLost -> STARTED" }
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    fun onWindowHidden() {
        Logger.d(tag = "DesktopLifecycleOwner") { "onWindowHidden -> CREATED" }
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onWindowClosed() {
        Logger.d(tag = "DesktopLifecycleOwner") { "onWindowClosed -> DESTROYED" }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}

/**
 * Creates and remembers a DesktopLifecycleOwner that tracks the window's focus state.
 * Must be called within a FrameWindowScope (inside a Window composable).
 */
@Composable
fun FrameWindowScope.rememberDesktopLifecycleOwner(
    isWindowVisible: Boolean
): DesktopLifecycleOwner {
    val lifecycleOwner = remember { DesktopLifecycleOwner() }

    // React to window visibility changes
    DisposableEffect(isWindowVisible) {
        if (isWindowVisible) {
            lifecycleOwner.onWindowVisible()
        } else {
            lifecycleOwner.onWindowHidden()
        }
        onDispose { }
    }

    // Track window focus with AWT listener
    DisposableEffect(window) {
        val listener = object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent?) {
                lifecycleOwner.onWindowFocusGained()
            }

            override fun windowLostFocus(e: WindowEvent?) {
                lifecycleOwner.onWindowFocusLost()
            }
        }

        window.addWindowFocusListener(listener)

        // Initialize state based on current focus
        if (window.isFocused) {
            lifecycleOwner.onWindowFocusGained()
        }

        onDispose {
            window.removeWindowFocusListener(listener)
            lifecycleOwner.onWindowClosed()
        }
    }

    return lifecycleOwner
}