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
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.unit.DpSize
import id.homebase.chat.calling.CallRingWindowContent
import id.homebase.chat.calling.LocalMacShortcuts
import id.homebase.chat.calling.RingWindowWidth
import id.homebase.chat.calling.CallTrayItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

val DesktopRingWindowSize = DpSize(RingWindowWidth, Dp.Unspecified)

private val isMac = System.getProperty("os.name").orEmpty().startsWith("Mac")

sealed interface DesktopCallTrayItem {
    data class Status(val name: String, val elapsed: String?) : DesktopCallTrayItem
    data class Mute(val muted: Boolean) : DesktopCallTrayItem
    data object HangUp : DesktopCallTrayItem
}

/** What desktopApp needs to host the call window; desktopApp cannot see homebase-chat types. */
interface DesktopCallWindow {
    val visible: StateFlow<Boolean>
    val ringing: StateFlow<Boolean>

    val live: StateFlow<Boolean>
    val muted: StateFlow<Boolean>
    val canToggleCamera: StateFlow<Boolean>

    val windowKey: StateFlow<String?>
    val trayItems: StateFlow<List<DesktopCallTrayItem>>
    fun focusCallWindow()
    fun isVideoCall(): Boolean
    fun requestClose()
    fun toggleMute()
    fun hangup()
    fun menuToggleMute()
    fun menuToggleVideo()
    fun menuToggleMini(toggle: () -> Unit)
    fun menuHangup()
    fun answer()
    fun decline()
    fun setMiniWindowOpen(open: Boolean)

    @Composable
    fun title(): String

    @Composable
    fun RingContent(modifier: Modifier)

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
    override val trayItems: StateFlow<List<DesktopCallTrayItem>> =
        model.trayItems.map { items -> items.map(::trayItem) }
            .stateIn(model.viewModel.viewModelScope, SharingStarted.WhileSubscribed(), emptyList())
    override fun focusCallWindow() = model.focusCallWindow()
    override fun isVideoCall() = model.isVideoCall()
    override fun requestClose() = model.requestClose()
    override fun toggleMute() = model.toggleMute()
    override fun hangup() = model.hangup()
    override fun menuToggleMute() = model.menuToggleMute()
    override fun menuToggleVideo() = model.menuToggleVideo()
    override fun menuToggleMini(toggle: () -> Unit) = model.menuToggleMini(toggle)
    override fun menuHangup() = model.menuHangup()
    override fun answer() = if (model.isVideoCall()) model.acceptVideo() else model.acceptVoice()
    override fun decline() = model.decline()
    override fun setMiniWindowOpen(open: Boolean) {
        model.miniWindowOpen.value = open
    }

    @Composable
    override fun title() = rememberCallWindowTitle(model)

    @Composable
    override fun RingContent(modifier: Modifier) {
        CompositionLocalProvider(LocalMiniWindowShape provides floatingWindowShape, LocalMacShortcuts provides isMac) {
            CallRingWindowContent(model, modifier)
        }
    }

    @Composable
    override fun Content(onMinimize: () -> Unit, topInset: Dp, mini: Boolean, onToggleMini: () -> Unit, onMiniDragStart: () -> Unit, onMiniDrag: () -> Unit) {
        CompositionLocalProvider(LocalMiniWindowShape provides floatingWindowShape) {
            CallWindowContent(
                model, onMinimize, topInset,
                mini = mini,
                onToggleMini = onToggleMini,
                onMiniDragStart = onMiniDragStart,
                onMiniDrag = onMiniDrag,
                macShortcuts = isMac,
            )
        }
    }
}

// desktopApp makes the mini and ring frames transparent on macOS only, so only there can the corners round.
private val floatingWindowShape = if (isMac) RoundedCornerShape(12.dp) else RectangleShape

private fun trayItem(item: CallTrayItem): DesktopCallTrayItem = when (item) {
    is CallTrayItem.Status -> DesktopCallTrayItem.Status(item.name, item.elapsed)
    is CallTrayItem.Mute -> DesktopCallTrayItem.Mute(item.muted)
    CallTrayItem.HangUp -> DesktopCallTrayItem.HangUp
}

