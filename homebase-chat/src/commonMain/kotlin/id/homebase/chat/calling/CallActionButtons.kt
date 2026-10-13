package id.homebase.chat.calling

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
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

@Composable
fun rememberCallStarter(conversation: EnrichedConversationUiModel): CallStarter? {
    val service = koinInject<CallService>().takeIf { it.available } ?: return null
    val status = conversation.oneOnOneConnectionStatus as? OneOnOneConnectionStatus.Connected ?: return null
    if (conversation.conversation.isGroupConversation || conversation.conversation.isWithSelf) return null
    val state by service.state.collectAsStateWithLifecycle()
    val gate = LocalCallPermissionGate.current
    val peer = remember(status) { status.otherOdinId.domainName }
    val start = remember(service, gate, peer) {
        { video: Boolean -> gate.run(video = video) { granted -> service.startCall(peer, video = granted) } }
    }
    return CallStarter(idle = !state.isActive, start = start)
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
