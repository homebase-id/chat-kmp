package id.homebase.chat.calling

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.services.calling.CallService
import id.homebase.chat.services.convo.EnrichedConversationUiModel
import id.homebase.chat.services.convo.OneOnOneConnectionStatus
import id.homebase.resources.MR
import id.homebase.resources.call_button_video
import id.homebase.resources.call_button_voice
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** The open conversation's [CallStarter], or null where its peer can't be called. */
val LocalCallStarter = compositionLocalOf<CallStarter?> { null }

data class CallStarter(val idle: Boolean, val start: (video: Boolean) -> Unit)

class PeerCallStarter(val idle: Boolean, val start: (peer: String, video: Boolean) -> Unit)

@Composable
fun rememberPeerCallStarter(): PeerCallStarter? {
    val service = koinInject<CallService>().takeIf { it.available } ?: return null
    val state = service.state.collectAsStateWithLifecycle()
    // Only idleness, so the conversation screen does not recompose on every call-state change.
    val idle by remember(state) { derivedStateOf { !state.value.isActive } }
    val gate = rememberCallPermissionGate()
    val start = remember(service, gate) {
        { peer: String, video: Boolean -> gate.run(video = video) { granted -> service.startCall(peer, video = granted) } }
    }
    return PeerCallStarter(idle = idle, start = start)
}

@Composable
fun rememberCallStarter(conversation: EnrichedConversationUiModel): CallStarter? {
    val status = conversation.oneOnOneConnectionStatus as? OneOnOneConnectionStatus.Connected ?: return null
    if (conversation.conversation.isGroupConversation || conversation.conversation.isWithSelf) return null
    val starter = rememberPeerCallStarter() ?: return null
    val peer = remember(status) { status.otherOdinId.domainName }
    return CallStarter(idle = starter.idle, start = { video -> starter.start(peer, video) })
}

@Composable
fun CallActionButtons(starter: CallStarter?) {
    starter ?: return
    IconButton(enabled = starter.idle, onClick = { starter.start(false) }) {
        Icon(Icons.Default.Call, contentDescription = stringResource(MR.string.call_button_voice))
    }
    IconButton(enabled = starter.idle, onClick = { starter.start(true) }) {
        Icon(Icons.Default.Videocam, contentDescription = stringResource(MR.string.call_button_video))
    }
}
