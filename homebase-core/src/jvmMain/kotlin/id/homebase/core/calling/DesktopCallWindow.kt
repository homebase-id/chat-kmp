package id.homebase.core.calling

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import id.homebase.chat.calling.CallWindowContent
import id.homebase.chat.calling.CallWindowModel
import id.homebase.chat.calling.rememberCallWindowTitle
import kotlinx.coroutines.flow.StateFlow

/** What desktopApp needs to host the call window; desktopApp cannot see homebase-chat types. */
interface DesktopCallWindow {
    val visible: StateFlow<Boolean>
    val ringing: StateFlow<Boolean>

    /** Changes with each call, so the window is recreated (and re-sized for its kind) rather than reused. */
    val windowKey: StateFlow<String?>
    fun isVideoCall(): Boolean
    fun requestClose()

    @Composable
    fun title(): String

    @Composable
    fun Content(onMinimize: () -> Unit, topInset: Dp)
}

class ModelDesktopCallWindow(private val model: CallWindowModel) : DesktopCallWindow {
    override val visible get() = model.windowVisible
    override val ringing get() = model.ringing
    override val windowKey get() = model.windowKey
    override fun isVideoCall() = model.isVideoCall()
    override fun requestClose() = model.requestClose()

    @Composable
    override fun title() = rememberCallWindowTitle(model)

    @Composable
    override fun Content(onMinimize: () -> Unit, topInset: Dp) =
        CallWindowContent(model, onMinimize, topInset)
}
