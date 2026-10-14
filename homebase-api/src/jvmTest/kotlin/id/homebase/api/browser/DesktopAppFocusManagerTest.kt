package id.homebase.api.browser

import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import javax.swing.JFrame
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAppFocusManagerTest {
    private val frames = mutableListOf<JFrame>()

    private fun frame(iconified: Boolean = false) = JFrame().also {
        it.setSize(200, 100)
        if (iconified) it.extendedState = Frame.ICONIFIED
        frames += it
    }

    private fun flushEdt() = EventQueue.invokeAndWait { }

    @AfterTest
    fun tearDown() {
        DesktopAppFocusManager.unregisterWindowProvider(WindowKeys.CALL)
        DesktopAppFocusManager.unregisterWindowProvider(WindowKeys.MAIN)
        EventQueue.invokeAndWait { frames.forEach { it.dispose() } }
    }

    @Test
    fun raisingTheCallWindowDeiconifiesItAndLeavesTheMainWindowAlone() {
        if (GraphicsEnvironment.isHeadless()) return
        val main = frame(iconified = true)
        val call = frame(iconified = true)
        DesktopAppFocusManager.registerWindowProvider { main }
        DesktopAppFocusManager.registerWindowProvider(WindowKeys.CALL) { call }

        DesktopAppFocusManager.requestFocus("test", WindowKeys.CALL)
        flushEdt()

        assertTrue(call.isVisible)
        assertEquals(0, call.extendedState and Frame.ICONIFIED)
        assertFalse(main.isVisible)
        assertEquals(Frame.ICONIFIED, main.extendedState and Frame.ICONIFIED)
    }

    @Test
    fun aRaiseBeforeTheCallWindowRegistersIsReplayedOnRegistration() {
        if (GraphicsEnvironment.isHeadless()) return
        val main = frame()
        val call = frame(iconified = true)
        DesktopAppFocusManager.registerWindowProvider { main }

        DesktopAppFocusManager.requestFocus("incoming-call", WindowKeys.CALL)
        flushEdt()
        assertFalse(main.isVisible, "an unregistered call window must not fall back to raising the main window")

        EventQueue.invokeAndWait { DesktopAppFocusManager.registerWindowProvider(WindowKeys.CALL) { call } }
        flushEdt()

        assertTrue(call.isVisible)
        assertEquals(0, call.extendedState and Frame.ICONIFIED)
        assertFalse(main.isVisible)
    }

    @Test
    fun aRaiseIsReplayedOnlyOnce() {
        if (GraphicsEnvironment.isHeadless()) return
        val call = frame()
        DesktopAppFocusManager.requestFocus("incoming-call", WindowKeys.CALL)
        flushEdt()
        EventQueue.invokeAndWait { DesktopAppFocusManager.registerWindowProvider(WindowKeys.CALL) { call } }
        flushEdt()
        EventQueue.invokeAndWait { call.isVisible = false; DesktopAppFocusManager.registerWindowProvider(WindowKeys.CALL) { call } }
        flushEdt()
        assertFalse(call.isVisible)
    }
}
