package id.homebase.app

import co.touchlab.kermit.Logger
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference
import id.homebase.core.util.HostOs
import id.homebase.core.util.hostOs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** NVIDIA's swap under XWayland can wait forever for a Present event after a monitor reconnect; a canvas resize from outside AWT releases it. */
internal object StuckPresentRecovery {
    const val STUCK_MS = 4_000L
    const val SWAP_CLASS = "org.jetbrains.skiko.renderer.LinuxOpenGLRendererKt"
    const val SWAP_METHOD = "swapBuffers"
    private const val TAG = "StuckPresentRecovery"
    private const val CANVAS_WINDOW_NAME = "sun-awt-X11-XCanvasPeer"
    private const val XA_CARDINAL = 6L
    private const val RETRY_MS = 2_000L

    internal val onX11 = hostOs == HostOs.Linux && !System.getenv("DISPLAY").isNullOrEmpty()

    /** Returns at once unless [ui] is stuck in Skiko's swap; otherwise nudges until [uiRan], which the window only allows once it's shown again. */
    fun releaseIfStuck(ui: Thread?, uiRan: CountDownLatch) {
        if (!onX11 || ui == null || !isStuckInSwap(ui.stackTrace)) return
        Logger.e(tag = TAG) { "UI thread stuck in Skiko $SWAP_METHOD; resizing its canvas windows every $RETRY_MS ms until it runs again" }
        val pid = ProcessHandle.current().pid()
        val rounds = nudgeUntil(uiRan, RETRY_MS) { nudgeCanvases(pid) }
        Logger.e(tag = TAG) { "UI thread running again after $rounds nudge round(s)" }
    }

    internal fun nudgeUntil(uiRan: CountDownLatch, retryMs: Long, nudge: () -> Unit): Int {
        var rounds = 0
        var failure: String? = null
        while (uiRan.count > 0) {
            try {
                nudge()
            } catch (e: Exception) {
                if (e.message != failure) Logger.e(e, TAG) { "couldn't reach the canvas windows" }
                failure = e.message
            }
            rounds++
            uiRan.await(retryMs, TimeUnit.MILLISECONDS)
        }
        return rounds
    }

    internal fun isStuckInSwap(uiStack: Array<StackTraceElement>): Boolean =
        uiStack.firstOrNull()?.let { it.className == SWAP_CLASS && it.methodName == SWAP_METHOD } == true

    /** How many of [pid]'s AWT canvas windows it shrank by a pixel and restored. */
    internal fun nudgeCanvases(pid: Long): Int {
        val x = Xlib.INSTANCE
        val display = x.XOpenDisplay(null) ?: error("can't open the X display")
        try {
            val pidAtom = x.XInternAtom(display, "_NET_WM_PID", 1)
            if (pidAtom.toLong() == 0L) return 0
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
