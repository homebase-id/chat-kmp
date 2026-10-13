@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package id.homebase.chat.calling

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.chat.services.calling.CallPhase
import id.homebase.resources.MR
import id.homebase.resources.call_camera_off
import id.homebase.resources.call_camera_on
import id.homebase.resources.call_hang_up
import id.homebase.resources.call_mini_expand
import id.homebase.resources.call_mute
import id.homebase.resources.call_state_camera_on
import id.homebase.resources.call_state_mic_on
import id.homebase.resources.call_state_muted
import id.homebase.resources.call_no_video
import id.homebase.resources.call_status_calling
import id.homebase.resources.call_status_connecting
import id.homebase.resources.call_status_reconnecting
import id.homebase.resources.call_status_ringing
import id.homebase.resources.call_unmute
import org.jetbrains.compose.resources.stringResource

val MiniVideoSize = 320.dp to 180.dp
val MiniVoiceSize = 280.dp to 88.dp

internal const val CallMiniTag = "call-mini"

@Composable
fun CallMiniContent(
    ui: CallUiState,
    peerName: String,
    avatar: @Composable (Dp) -> Unit,
    actions: CallScreenActions,
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit = {},
    onDrag: () -> Unit = {},
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val expand = actions.toggleMini ?: {}
    val gate = LocalShortcutGate.current
    CompositionLocalProvider(LocalDesktopTier provides DesktopTier.Minimal) {
        Box(
            modifier.fillMaxSize()
                .testTag(CallMiniTag)
                .background(MaterialTheme.colorScheme.surface)
                .miniWindowGestures(onDragStart, onDrag, expand)
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { handleCallKey(it, ui, actions, textFocused = true, gate = gate) },
        ) {
            if (ui.isVideoCall) MiniVideo(ui, peerName, avatar, actions) else MiniVoice(ui, peerName, avatar, actions)
            ExpandButton(expand, Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
    }
}

private fun Modifier.miniWindowGestures(onDragStart: () -> Unit, onDrag: () -> Unit, onDoubleTap: () -> Unit): Modifier =
    pointerInput(onDragStart, onDrag, onDoubleTap) {
        var lastTapAt = Long.MIN_VALUE
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            var dragging = false
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed) break
                if (!change.pressed) {
                    if (!dragging) {
                        val doubled = change.uptimeMillis - lastTapAt <= viewConfiguration.doubleTapTimeoutMillis
                        lastTapAt = if (doubled) Long.MIN_VALUE else change.uptimeMillis
                        if (doubled) onDoubleTap()
                    }
                    break
                }
                if (!dragging && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    dragging = true
                    onDragStart()
                }
                if (dragging) onDrag()
            }
        }
    }

@Composable
private fun ExpandButton(onClick: () -> Unit, modifier: Modifier) {
    val label = stringResource(MR.string.call_mini_expand)
    Box(modifier) {
        DesktopTooltip(label, androidx.compose.material3.TooltipAnchorPosition.Below) {
            FilledTonalIconButton(
                onClick = onClick,
                modifier = Modifier.size(24.dp).buttonChrome(CircleShape),
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = chromeColor(), contentColor = MaterialTheme.colorScheme.onSurface),
            ) { Icon(Icons.Default.OpenInFull, label, Modifier.size(14.dp)) }
        }
    }
}

@Composable
private fun miniStatus(ui: CallUiState, elapsed: String): String = when (ui.phase) {
    CallPhase.Outgoing -> stringResource(if (ui.peerRinging) MR.string.call_status_ringing else MR.string.call_status_calling)
    CallPhase.Connecting -> stringResource(MR.string.call_status_connecting)
    CallPhase.Reconnecting -> stringResource(MR.string.call_status_reconnecting)
    else -> elapsed
}

@Composable
private fun MiniVoice(ui: CallUiState, peerName: String, avatar: @Composable (Dp) -> Unit, actions: CallScreenActions) {
    val status = miniStatus(ui, rememberElapsedText(ui.connectedAtMs))
    Row(
        Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { avatar(52.dp) }
        Column(Modifier.weight(1f)) {
            Text(peerName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, style = MaterialTheme.typography.labelLarge.tabular(), color = MaterialTheme.colorScheme.primary, maxLines = 1)
        }
        MiniMute(ui, actions)
        EndCallButton(actions.hangup, Modifier.size(width = 48.dp, height = 40.dp))
    }
}

@Composable
private fun MiniVideo(ui: CallUiState, peerName: String, avatar: @Composable (Dp) -> Unit, actions: CallScreenActions) {
    val status = miniStatus(ui, rememberElapsedText(ui.connectedAtMs))
    Box(Modifier.fillMaxSize()) {
        if (ui.remoteShowing()) {
            RemoteVideo(ui.videos.remote)
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) { avatar(64.dp) }
        }
        Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            shape = RoundedCornerShape(16.dp),
            color = chromeColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(peerName, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 0.dp))
                Text(status, style = MaterialTheme.typography.labelLarge.tabular(), color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            shape = RoundedCornerShape(24.dp),
            color = chromeColor(),
        ) {
            Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                MiniMute(ui, actions)
                if (ui.videos.local != null) MiniCamera(ui, actions)
                EndCallButton(actions.hangup, Modifier.size(width = 52.dp, height = 40.dp))
            }
        }
    }
}

@Composable
private fun MiniMute(ui: CallUiState, actions: CallScreenActions) {
    val label = stringResource(if (ui.muted) MR.string.call_unmute else MR.string.call_mute)
    val state = stringResource(if (ui.muted) MR.string.call_state_muted else MR.string.call_state_mic_on)
    DesktopTooltip(shortcutTip(label, callShortcutKeys("D"))) {
        FilledTonalIconToggleButton(
            checked = ui.muted,
            onCheckedChange = { actions.toggleMute() },
            colors = miniToggleColors(),
            modifier = Modifier.size(40.dp).buttonChrome(CircleShape).semantics { contentDescription = label; stateDescription = state },
        ) { Icon(if (ui.muted) Icons.Default.MicOff else Icons.Default.Mic, null, Modifier.size(22.dp)) }
    }
}

@Composable
private fun MiniCamera(ui: CallUiState, actions: CallScreenActions) {
    val off = !ui.localVideo
    val label = stringResource(if (off) MR.string.call_camera_on else MR.string.call_camera_off)
    val state = stringResource(if (off) MR.string.call_no_video else MR.string.call_state_camera_on)
    DesktopTooltip(shortcutTip(label, callShortcutKeys("E"))) {
        FilledTonalIconToggleButton(
            checked = off,
            onCheckedChange = { actions.toggleVideo() },
            colors = miniToggleColors(),
            modifier = Modifier.size(40.dp).buttonChrome(CircleShape).semantics { contentDescription = label; stateDescription = state },
        ) { Icon(if (off) Icons.Default.VideocamOff else Icons.Default.Videocam, null, Modifier.size(22.dp)) }
    }
}

@Composable
private fun miniToggleColors() = IconButtonDefaults.filledTonalIconToggleButtonColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor = MaterialTheme.colorScheme.onSurface,
    checkedContainerColor = MaterialTheme.colorScheme.inverseSurface,
    checkedContentColor = MaterialTheme.colorScheme.inverseOnSurface,
)
