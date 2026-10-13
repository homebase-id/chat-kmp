package id.homebase.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.app.lifecycle.DesktopLifecycleOwner
import kotlinx.coroutines.delay
import id.homebase.core.calling.DesktopCallTrayItem
import id.homebase.core.calling.DesktopCallWindow
import id.homebase.resources.MR
import id.homebase.resources.desktop_menu_call_hang_up
import id.homebase.resources.desktop_menu_call_mute
import id.homebase.resources.desktop_menu_call_unmute
import id.homebase.resources.desktop_tray_in_call
import id.homebase.resources.desktop_tray_in_call_timed
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.Taskbar
import org.jetbrains.compose.resources.stringResource

enum class CallMenuAction { OpenCall, ToggleMute, HangUp }

data class CallMenuEntry(val action: CallMenuAction, val label: String)

class CallMenuLabels(
    val inCall: (name: String) -> String,
    val inCallTimed: (name: String, elapsed: String) -> String,
    val mute: String,
    val unmute: String,
    val hangUp: String,
)

fun callMenuEntries(items: List<DesktopCallTrayItem>, labels: CallMenuLabels): List<CallMenuEntry> = items.map { item ->
    when (item) {
        is DesktopCallTrayItem.Status -> CallMenuEntry(
            CallMenuAction.OpenCall,
            item.elapsed?.let { labels.inCallTimed(item.name, it) } ?: labels.inCall(item.name),
        )
        is DesktopCallTrayItem.Mute -> CallMenuEntry(CallMenuAction.ToggleMute, if (item.muted) labels.unmute else labels.mute)
        DesktopCallTrayItem.HangUp -> CallMenuEntry(CallMenuAction.HangUp, labels.hangUp)
    }
}

fun DesktopCallWindow.perform(action: CallMenuAction) = when (action) {
    CallMenuAction.OpenCall -> focusCallWindow()
    CallMenuAction.ToggleMute -> toggleMute()
    CallMenuAction.HangUp -> hangup()
}

@Composable
fun rememberCallMenuEntries(items: List<DesktopCallTrayItem>): List<CallMenuEntry> {
    val inCall = stringResource(MR.string.desktop_tray_in_call, "%1\$s")
    val inCallTimed = stringResource(MR.string.desktop_tray_in_call_timed, "%1\$s", "%2\$s")
    val mute = stringResource(MR.string.desktop_menu_call_mute)
    val unmute = stringResource(MR.string.desktop_menu_call_unmute)
    val hangUp = stringResource(MR.string.desktop_menu_call_hang_up)
    return remember(items, inCall, inCallTimed, mute, unmute, hangUp) {
        callMenuEntries(
            items,
            CallMenuLabels(
                inCall = { inCall.replace("%1\$s", it) },
                inCallTimed = { name, elapsed -> inCallTimed.replace("%1\$s", name).replace("%2\$s", elapsed) },
                mute = mute, unmute = unmute, hangUp = hangUp,
            ),
        )
    }
}

private const val MENU_REFRESH_MS = 15_000L

/** A native menu rebuilt every second closes or flickers under the pointer; the timer text only catches up every [MENU_REFRESH_MS]. */
@Composable
fun rememberSettledCallItems(items: List<DesktopCallTrayItem>): List<DesktopCallTrayItem> {
    var settled by remember { mutableStateOf(items) }
    val latest by rememberUpdatedState(items)
    LaunchedEffect(items.map(::withoutTimer)) { settled = latest }
    LaunchedEffect(Unit) {
        while (true) {
            delay(MENU_REFRESH_MS)
            settled = latest
        }
    }
    return settled
}

internal fun withoutTimer(item: DesktopCallTrayItem): DesktopCallTrayItem =
    if (item is DesktopCallTrayItem.Status) item.copy(elapsed = null) else item

/** macOS has no tray icon, so the in-call controls live on the Dock icon. */
@Composable
fun CallDockMenu(entries: List<CallMenuEntry>, perform: (CallMenuAction) -> Unit) {
    if (!isMacOs) return
    val latestEntries by rememberUpdatedState(entries)
    val latestPerform by rememberUpdatedState(perform)
    val statusItem = remember { arrayOfNulls<MenuItem>(1) }
    DisposableEffect(entries.map { if (it.action == CallMenuAction.OpenCall) it.action to null else it.action to it.label }) {
        val taskbar = runCatching { Taskbar.getTaskbar() }.getOrNull()?.takeIf { it.isSupported(Taskbar.Feature.MENU) }
        if (taskbar != null && latestEntries.isNotEmpty()) {
            val menu = PopupMenu()
            latestEntries.forEach { entry ->
                val item = MenuItem(entry.label).apply { addActionListener { latestPerform(entry.action) } }
                if (entry.action == CallMenuAction.OpenCall) statusItem[0] = item
                menu.add(item)
            }
            taskbar.menu = menu
        }
        onDispose {
            statusItem[0] = null
            if (taskbar != null) taskbar.menu = null
        }
    }
    SideEffect {
        val label = entries.firstOrNull { it.action == CallMenuAction.OpenCall }?.label
        val item = statusItem[0]
        if (label != null && item != null && item.label != label) item.label = label
    }
}

@Composable
fun collectCallTrayItems(call: DesktopCallWindow): State<List<DesktopCallTrayItem>> {
    val owner = remember { DesktopLifecycleOwner().also { it.onWindowVisible() } }
    DisposableEffect(owner) { onDispose { owner.onWindowClosed() } }
    return call.trayItems.collectAsStateWithLifecycle(lifecycleOwner = owner)
}
