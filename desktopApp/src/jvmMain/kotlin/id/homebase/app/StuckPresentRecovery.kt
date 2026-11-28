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
    private const val MAX_FRAME_DEPTH = 3

    internal val onX11 = hostOs == HostOs.Linux && !System.getenv("DISPLAY").isNullOrEmpty()

    /** Until [uiRan]: every [RETRY_MS], nudges the canvases if the UI thread is in Skiko's swap; the window only lets go once it's shown again. */
    fun recoverWhileStuck(uiRan: CountDownLatch) {
        if (!onX11) return
        val pid = ProcessHandle.current().pid()
        var announced = false
        var missingLogged = false
        val nudges = nudgeWhileStuck(uiRan, RETRY_MS, stuck = { awtEventThread()?.let { isStuckInSwap(it.stackTrace) } == true }) {
            if (!announced) Logger.e(tag = TAG) { "UI thread stuck in Skiko $SWAP_METHOD; resizing its canvas windows every $RETRY_MS ms until it runs again" }
            announced = true
            if (nudgeCanvases(pid) == 0 && !missingLogged) {
                Logger.e(tag = TAG) { "no canvas window of this process found" }
                missingLogged = true
            }
        }
        if (announced && uiRan.count == 0L) Logger.e(tag = TAG) { "UI thread running again after $nudges nudge(s)" }
    }

    /** The nudges sent; a failure is logged once and retried, unless JNA or libX11 can't load. */
    internal fun nudgeWhileStuck(uiRan: CountDownLatch, retryMs: Long, stuck: () -> Boolean, nudge: () -> Unit): Int {
        var nudges = 0
        var failure: String? = null
        while (uiRan.count > 0) {
            try {
                if (stuck()) {
                    nudges++
                    nudge()
                }
            } catch (e: LinkageError) {
                Logger.e(e, TAG) { "can't load JNA or libX11; no recovery" }
                return nudges
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
                if (e.toString() != failure) Logger.e(e, TAG) { "couldn't reach the canvas windows" }
                failure = e.toString()
            }
            uiRan.await(retryMs, TimeUnit.MILLISECONDS)
        }
        return nudges
    }

    internal fun isStuckInSwap(uiStack: Array<StackTraceElement>): Boolean =
        uiStack.firstOrNull()?.let { it.className == SWAP_CLASS && it.methodName == SWAP_METHOD } == true

    // Enumerates threads without stacks; AWT starts a new one after an event throws.
    private fun awtEventThread(): Thread? {
        var group = Thread.currentThread().threadGroup
        while (group.parent != null) group = group.parent
        val threads = arrayOfNulls<Thread>(group.activeCount() + 16)
        val count = group.enumerate(threads, true)
        return threads.take(count).firstOrNull { it != null && it.isAlive && it.name.startsWith("AWT-EventQueue") }
    }

    /** How many of [pid]'s AWT canvas windows it nudged by a pixel and restored; [whileNudged] runs between the two. */
    internal fun nudgeCanvases(pid: Long, whileNudged: () -> Unit = {}): Int = withCanvases(pid) { x, display, canvases ->
        val nudged = canvases.mapNotNull { canvas -> x.size(display, canvas)?.let { canvas to it } }
        nudged.forEach { (canvas, size) -> x.XResizeWindow(display, canvas, size.first, if (size.second > 1) size.second - 1 else size.second + 1) }
        x.XSync(display, 0)
        whileNudged()
        nudged.forEach { (canvas, size) -> x.XResizeWindow(display, canvas, size.first, size.second) }
        x.XSync(display, 0)
        nudged.size
    }

    internal fun canvasSizes(pid: Long): List<Pair<Int, Int>> = withCanvases(pid) { x, display, canvases -> canvases.mapNotNull { x.size(display, it) } }

    private fun <T> withCanvases(pid: Long, block: (Xlib, Pointer, List<NativeLong>) -> T): T {
        val x = Xlib.INSTANCE
        val display = x.XOpenDisplay(null) ?: error("can't open the X display")
        try {
            val pidAtom = x.XInternAtom(display, "_NET_WM_PID", 1)
            if (pidAtom.toLong() == 0L) return block(x, display, emptyList())
            val clients = x.clientsOf(display, x.XDefaultRootWindow(display), pidAtom, pid, depth = 0)
            return block(x, display, clients.flatMap { x.canvasesUnder(display, it) })
        } finally {
            x.XCloseDisplay(display)
        }
    }

    // Reparenting window managers put the client, which carries the pid, under their frames (KWin on X11: frame, then wrapper).
    private fun Xlib.clientsOf(display: Pointer, parent: NativeLong, pidAtom: NativeLong, pid: Long, depth: Int): List<NativeLong> =
        children(display, parent).flatMap { child ->
            when {
                windowPid(display, child, pidAtom) == pid -> listOf(child)
                depth < MAX_FRAME_DEPTH -> clientsOf(display, child, pidAtom, pid, depth + 1)
                else -> emptyList()
            }
        }

    private fun Xlib.size(display: Pointer, window: NativeLong): Pair<Int, Int>? {
        val width = IntByReference()
        val height = IntByReference()
        if (XGetGeometry(display, window, NativeLongByReference(), IntByReference(), IntByReference(), width, height, IntByReference(), IntByReference()) == 0) return null
        return width.value to height.value
    }

    private fun Xlib.children(display: Pointer, window: NativeLong): List<NativeLong> {
        val list = PointerByReference()
        val count = IntByReference()
        if (XQueryTree(display, window, NativeLongByReference(), NativeLongByReference(), list, count) == 0) return emptyList()
        val pointer = list.value ?: return emptyList()
        return try {
            (0 until count.value).map { pointer.getNativeLong(it.toLong() * NativeLong.SIZE) }
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
