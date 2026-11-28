package id.homebase.app

import co.touchlab.kermit.Logger
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * After a monitor reconnects under XWayland, NVIDIA's `glXSwapBuffers` can wait forever for a Present
 * event that never comes, holding AWT's lock, so nothing inside AWT can help. Resizing the app's
 * canvas windows over a separate X connection makes XWayland let the swap return, but only once
 * the window can be shown again (not while the screen is locked), so it is retried until the UI
 * thread runs again.
 */
internal object StuckPresentRecovery {
    private const val TAG = "StuckPresentRecovery"
    private const val CANVAS_WINDOW_NAME = "sun-awt-X11-XCanvasPeer"
    private const val XA_CARDINAL = 6L
    private const val RETRY_MS = 2_000L

    private val onX11 = System.getProperty("os.name").startsWith("Linux") && !System.getenv("DISPLAY").isNullOrEmpty()
    private val recovering = AtomicBoolean(false)

    fun onMainThreadBlocked() {
        val uiStack = Thread.getAllStackTraces().entries.firstOrNull { it.key.name.startsWith("AWT-EventQueue") }?.value
        if (!onX11 || !isStuckInSwap(uiStack) || !recovering.compareAndSet(false, true)) return
        val responded = AtomicBoolean(false)
        // Posting doesn't take AWT's lock, so it works while the swap holds it.
        EventQueue.invokeLater { responded.set(true) }
        Logger.e(tag = TAG) { "UI thread stuck in Skiko swapBuffers; resizing its canvas windows every $RETRY_MS ms until it runs again" }
        thread(name = TAG, isDaemon = true) {
            try {
                val start = System.nanoTime()
                val rounds = nudgeUntil(responded, nudge = { nudgeCanvases(ProcessHandle.current().pid()) }, pause = { Thread.sleep(RETRY_MS) })
                Logger.e(tag = TAG) { "UI thread running again after $rounds nudge round(s), ${(System.nanoTime() - start) / 1_000_000} ms" }
            } finally {
                recovering.set(false)
            }
        }
    }

    /** Nudges, then pauses, until [responded]; a failing nudge is logged once and retried. The rounds run. */
    internal fun nudgeUntil(responded: AtomicBoolean, nudge: () -> Int, pause: () -> Unit): Int {
        var rounds = 0
        var failure: String? = null
        while (!responded.get()) {
            try {
                nudge()
            } catch (e: Exception) {
                if (e.message != failure) Logger.e(e, TAG) { "couldn't reach the canvas windows" }
                failure = e.message
            }
            rounds++
            pause()
        }
        return rounds
    }

    internal fun isStuckInSwap(uiStack: Array<StackTraceElement>?): Boolean =
        uiStack?.firstOrNull()?.let { it.className == "org.jetbrains.skiko.renderer.LinuxOpenGLRendererKt" && it.methodName == "swapBuffers" } == true

    /** Shrinks each of [pid]'s AWT canvas windows by a pixel and restores it; how many there were. */
    internal fun nudgeCanvases(pid: Long): Int {
        val x = Xlib.INSTANCE
        val display = x.XOpenDisplay(null) ?: error("can't open the X display")
        try {
            val pidAtom = x.XInternAtom(display, "_NET_WM_PID", 1)
            val canvases = x.children(display, x.XDefaultRootWindow(display))
                .filter { x.windowPid(display, it, pidAtom) == pid }
                .flatMap { x.canvasesUnder(display, it) }
            canvases.forEach { canvas ->
                val width = IntByReference()
                val height = IntByReference()
                if (x.XGetGeometry(display, canvas, NativeLongByReference(), IntByReference(), IntByReference(), width, height, IntByReference(), IntByReference()) == 0) return@forEach
                x.XResizeWindow(display, canvas, width.value, maxOf(1, height.value - 1))
                x.XSync(display, 0)
                x.XResizeWindow(display, canvas, width.value, height.value)
            }
            x.XSync(display, 0)
            return canvases.size
        } finally {
            x.XCloseDisplay(display)
        }
    }

    private fun Xlib.children(display: Pointer, window: NativeLong): List<NativeLong> {
        val list = PointerByReference()
        val count = IntByReference()
        if (XQueryTree(display, window, NativeLongByReference(), NativeLongByReference(), list, count) == 0) return emptyList()
        val pointer = list.value ?: return emptyList()
        return try {
            pointer.getLongArray(0, count.value).map(::NativeLong)
        } finally {
            XFree(pointer)
        }
    }

    private fun Xlib.canvasesUnder(display: Pointer, window: NativeLong): List<NativeLong> =
        children(display, window).flatMap { child -> if (name(display, child) == CANVAS_WINDOW_NAME) listOf(child) else canvasesUnder(display, child) }

    private fun Xlib.name(display: Pointer, window: NativeLong): String? {
        val name = PointerByReference()
        if (XFetchName(display, window, name) == 0) return null
        val pointer = name.value ?: return null
        return try {
            pointer.getString(0)
        } finally {
            XFree(pointer)
        }
    }

    private fun Xlib.windowPid(display: Pointer, window: NativeLong, pidAtom: NativeLong): Long? {
        if (pidAtom.toLong() == 0L) return null
        val prop = PointerByReference()
        val items = NativeLongByReference()
        val status = XGetWindowProperty(
            display, window, pidAtom, NativeLong(0), NativeLong(1), 0, NativeLong(XA_CARDINAL),
            NativeLongByReference(), IntByReference(), items, NativeLongByReference(), prop,
        )
        val pointer = prop.value ?: return null
        return try {
            if (status == 0 && items.value.toLong() == 1L) pointer.getNativeLong(0).toLong() else null
        } finally {
            XFree(pointer)
        }
    }

    @Suppress("FunctionName")
    private interface Xlib : Library {
        fun XOpenDisplay(name: String?): Pointer?
        fun XCloseDisplay(display: Pointer): Int
        fun XDefaultRootWindow(display: Pointer): NativeLong
        fun XInternAtom(display: Pointer, name: String, onlyIfExists: Int): NativeLong
        fun XQueryTree(display: Pointer, window: NativeLong, root: NativeLongByReference, parent: NativeLongByReference, children: PointerByReference, count: IntByReference): Int
        fun XFetchName(display: Pointer, window: NativeLong, name: PointerByReference): Int
        fun XGetWindowProperty(
            display: Pointer, window: NativeLong, property: NativeLong, offset: NativeLong, length: NativeLong, delete: Int, type: NativeLong,
            actualType: NativeLongByReference, actualFormat: IntByReference, items: NativeLongByReference, bytesAfter: NativeLongByReference, prop: PointerByReference,
        ): Int
        fun XGetGeometry(
            display: Pointer, window: NativeLong, root: NativeLongByReference, x: IntByReference, y: IntByReference,
            width: IntByReference, height: IntByReference, border: IntByReference, depth: IntByReference,
        ): Int
        fun XResizeWindow(display: Pointer, window: NativeLong, width: Int, height: Int): Int
        fun XSync(display: Pointer, discard: Int): Int
        fun XFree(data: Pointer): Int

        companion object {
            val INSTANCE: Xlib by lazy { Native.load("X11", Xlib::class.java) }
        }
    }
}
