package id.homebase.core.camera

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/**
 * The camera stays over the app after a capture until the screen that receives it has drawn it, so the chat under
 * the camera never shows between the shutter and the editor. Epochs make a signal that lands before the camera
 * starts waiting still count.
 */
object CaptureHandoff {
    private val requested = MutableStateFlow(0)
    private val shown = MutableStateFlow(0)

    internal fun begin(): Int = requested.updateAndGet { it + 1 }

    /** Call once the capture's content (or the editor it joins) is on screen; a no-op when no camera is waiting. */
    fun contentShown() {
        val epoch = requested.value
        shown.update { maxOf(it, epoch) }
    }

    internal suspend fun awaitShown(epoch: Int) {
        shown.first { it >= epoch }
    }
}
