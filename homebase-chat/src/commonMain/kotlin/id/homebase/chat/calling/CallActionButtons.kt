package id.homebase.chat.calling

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
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
import org.koin.compose.getKoin

/** Voice and video call buttons for the chat header; render nothing unless this is a connected 1:1 on a calling-capable target. */
@Composable
fun CallActionButtons(conversation: EnrichedConversationUiModel) {
    val service = getKoin().getOrNull<CallService>()?.takeIf { it.available } ?: return
    val status = conversation.oneOnOneConnectionStatus as? OneOnOneConnectionStatus.Connected ?: return
    if (conversation.conversation.isGroupConversation || conversation.conversation.isWithSelf) return
    val state by service.state.collectAsStateWithLifecycle()
    val gate = rememberCallPermissionGate()
    val peer = remember(status) { status.otherOdinId.domainName }
    val idle = !state.isActive

    IconButton(enabled = idle, onClick = { gate.run(video = false) { service.startCall(peer, video = false) } }) {
        Icon(Icons.Default.Call, contentDescription = stringResource(MR.string.call_button_voice))
    }
    IconButton(enabled = idle, onClick = { gate.run(video = true) { video -> service.startCall(peer, video = video) } }) {
        Icon(Icons.Default.Videocam, contentDescription = stringResource(MR.string.call_button_video))
    }
}
