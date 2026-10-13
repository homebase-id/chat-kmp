package id.homebase.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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

/** macOS has no tray icon, so the in-call controls live on the Dock icon. */
@Composable
fun CallDockMenu(entries: List<CallMenuEntry>, perform: (CallMenuAction) -> Unit) {
    if (!isMacOs) return
    DisposableEffect(entries) {
        val taskbar = runCatching { Taskbar.getTaskbar() }.getOrNull()?.takeIf { it.isSupported(Taskbar.Feature.MENU) }
        if (taskbar != null && entries.isNotEmpty()) {
            val menu = PopupMenu()
            entries.forEach { entry ->
                menu.add(MenuItem(entry.label).apply { addActionListener { perform(entry.action) } })
            }
            taskbar.menu = menu
        }
        onDispose { if (taskbar != null) taskbar.menu = null }
    }
}
