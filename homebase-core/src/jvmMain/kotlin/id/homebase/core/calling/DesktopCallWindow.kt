package id.homebase.core.calling

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.chat.calling.CallWindowContent
import id.homebase.chat.calling.CallWindowModel
import id.homebase.chat.calling.LocalMiniWindowShape
import id.homebase.chat.calling.rememberCallWindowTitle
import kotlinx.coroutines.flow.StateFlow

/** What desktopApp needs to host the call window; desktopApp cannot see homebase-chat types. */
interface DesktopCallWindow {
    val visible: StateFlow<Boolean>
    val ringing: StateFlow<Boolean>

    val live: StateFlow<Boolean>
    val muted: StateFlow<Boolean>
    val canToggleCamera: StateFlow<Boolean>

    val windowKey: StateFlow<String?>
    fun isVideoCall(): Boolean
    fun requestClose()
    fun toggleMute()
    fun toggleVideo()
    fun hangup()
    fun menuToggleMute()
    fun menuToggleVideo()
    fun menuToggleMini(toggle: () -> Unit)

    @Composable
    fun title(): String

    @Composable
    fun Content(onMinimize: () -> Unit, topInset: Dp, mini: Boolean, onToggleMini: () -> Unit, onMiniDragStart: () -> Unit, onMiniDrag: () -> Unit)
}

class ModelDesktopCallWindow(private val model: CallWindowModel) : DesktopCallWindow {
    override val visible get() = model.windowVisible
    override val ringing get() = model.ringing
    override val live get() = model.live
    override val muted get() = model.muted
    override val canToggleCamera get() = model.canToggleCamera
    override val windowKey get() = model.windowKey
    override fun isVideoCall() = model.isVideoCall()
    override fun requestClose() = model.requestClose()
    override fun toggleMute() = model.toggleMute()
    override fun toggleVideo() = model.toggleVideo()
    override fun hangup() = model.hangup()
    override fun menuToggleMute() = model.menuToggleMute()
    override fun menuToggleVideo() = model.menuToggleVideo()
    override fun menuToggleMini(toggle: () -> Unit) = model.menuToggleMini(toggle)

    @Composable
    override fun title() = rememberCallWindowTitle(model)

    @Composable
    override fun Content(onMinimize: () -> Unit, topInset: Dp, mini: Boolean, onToggleMini: () -> Unit, onMiniDragStart: () -> Unit, onMiniDrag: () -> Unit) {
        val mac = System.getProperty("os.name").orEmpty().startsWith("Mac")
        // desktopApp makes the mini frame transparent on macOS only, so only there can the corners round.
        CompositionLocalProvider(LocalMiniWindowShape provides if (mac) RoundedCornerShape(12.dp) else RectangleShape) {
            CallWindowContent(
                model, onMinimize, topInset,
                mini = mini,
                onToggleMini = onToggleMini,
                onMiniDragStart = onMiniDragStart,
                onMiniDrag = onMiniDrag,
                macShortcuts = mac,
            )
        }
    }
}
