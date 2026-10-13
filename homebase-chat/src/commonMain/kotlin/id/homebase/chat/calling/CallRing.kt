package id.homebase.chat.calling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.resources.MR
import id.homebase.resources.call_decline
import id.homebase.resources.call_incoming_video
import id.homebase.resources.call_incoming_voice
import id.homebase.resources.call_ring_accept_video
import id.homebase.resources.call_ring_accept_voice
import org.jetbrains.compose.resources.stringResource

val RingWindowWidth = 360.dp
val RingWindowHeight = 200.dp

@Composable
fun CallRingWindowContent(model: CallWindowModel, modifier: Modifier = Modifier) {
    val ui by model.uiState.collectAsStateWithLifecycle()
    val name = model.peerName(ui.peer)
    CallRingContent(
        video = ui.isVideoCall,
        name = name,
        avatar = { size -> ui.peer?.let { PeerAvatar(it, name, size) } },
        onAcceptVoice = model::acceptVoice,
        onAcceptVideo = model::acceptVideo,
        onDecline = model::decline,
        modifier = modifier,
    )
}

@Composable
fun CallRingContent(
    video: Boolean,
    name: String,
    avatar: @Composable (Dp) -> Unit,
    onAcceptVoice: () -> Unit,
    onAcceptVideo: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            avatar(48.dp)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(if (video) MR.string.call_incoming_video else MR.string.call_incoming_voice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onAcceptVoice, modifier = Modifier.weight(1f), contentPadding = RingButtonPadding) {
                    ButtonLabel(stringResource(MR.string.call_ring_accept_voice))
                }
                Button(onClick = onAcceptVideo, modifier = Modifier.weight(1f), contentPadding = RingButtonPadding) {
                    ButtonLabel(stringResource(MR.string.call_ring_accept_video))
                }
            }
            Button(
                onClick = onDecline,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = RingButtonPadding,
                colors = ButtonDefaults.buttonColors(containerColor = endCallContainer(), contentColor = endCallContent()),
            ) { ButtonLabel(stringResource(MR.string.call_decline)) }
        }
    }
}

private val RingButtonPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)

@Composable
private fun ButtonLabel(text: String) = Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
