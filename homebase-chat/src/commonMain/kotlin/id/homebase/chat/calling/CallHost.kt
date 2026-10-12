package id.homebase.chat.calling

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.contacts.ContactInfoGateway
import id.homebase.api.common.OdinId
import id.homebase.chat.services.calling.CallEndReason
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.CallRingEffect
import id.homebase.chat.services.calling.CallService
import id.homebase.chat.services.calling.CallState
import id.homebase.chat.services.calling.CallVideos
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ContactAvatar
import id.homebase.core.util.initials
import id.homebase.resources.MR
import id.homebase.resources.call_verification_body
import id.homebase.resources.call_verification_ok
import id.homebase.resources.call_verification_title
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

private const val ENDED_LINGER_MS = 1_800L
private const val ENDED_FAILURE_LINGER_MS = 3_500L

/** Full-screen call surface for the single [CallService]; hosted once at the app root, hidden when calling is unsupported. */
@Composable
fun CallHost(modifier: Modifier = Modifier) {
    val service = koinInject<CallService>()
    if (!service.available) return
    val state by service.state.collectAsStateWithLifecycle()
    val videos by service.videos.collectAsStateWithLifecycle()
    CallHostContent(service, state, videos, modifier)
}

@Composable
private fun CallHostContent(service: CallService, state: CallState, videos: CallVideos, modifier: Modifier) {
    var dismissedCallId by remember { mutableStateOf<String?>(null) }
    val phase = state.phase
    LaunchedEffect(phase is CallPhase.Ended, state.callId) {
        if (phase is CallPhase.Ended) {
            delay(if (phase.reason is CallEndReason.Failed) ENDED_FAILURE_LINGER_MS else ENDED_LINGER_MS)
            dismissedCallId = state.callId
        }
    }
    val visible = phase != CallPhase.Idle && !(phase is CallPhase.Ended && state.callId == dismissedCallId)

    val gate = rememberCallPermissionGate()
    var showVerification by remember { mutableStateOf(false) }
    val actions = remember(service) {
        CallScreenActions(
            accept = {
                val wantsVideo = service.state.value.media.localVideo
                gate.run(video = wantsVideo) { video ->
                    if (wantsVideo && !video) service.setVideo(false)
                    service.accept()
                }
            },
            decline = service::decline,
            hangup = service::hangup,
            setMuted = { service.setMuted(it) },
            setVideo = { service.setVideo(it) },
            switchCamera = service::switchCamera,
            setRoute = { service.setAudioRoute(it) },
            onVerificationClick = { showVerification = true },
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 8 },
        exit = fadeOut() + slideOutVertically { it / 8 },
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize()) {
            val peer = state.peer
            val name = rememberPeerName(peer)
            CallRingEffect(ringing = phase == CallPhase.Incoming, callerName = name)
            CallScreen(
                state = state,
                videos = videos,
                peerName = name,
                avatar = { size -> peer?.let { PeerAvatar(it, name, size) } },
                actions = actions,
            )
        }
    }

    if (showVerification) {
        AlertDialog(
            onDismissRequest = { showVerification = false },
            title = { Text(stringResource(MR.string.call_verification_title)) },
            text = { Text(stringResource(MR.string.call_verification_body)) },
            confirmButton = {
                TextButton(onClick = { showVerification = false }) { Text(stringResource(MR.string.call_verification_ok)) }
            },
        )
    }
}

@Composable
internal fun rememberPeerName(odinId: String?): String {
    if (odinId == null) return ""
    val gateway = koinInject<ContactInfoGateway>()
    var name by remember(odinId) { mutableStateOf(odinId) }
    LaunchedEffect(odinId) {
        runCatching { gateway.displayName(OdinId(odinId)) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { name = it }
    }
    return name
}

@Composable
internal fun PeerAvatar(odinId: String, name: String, size: Dp) {
    ContactAvatar(
        odinId = OdinId(odinId),
        profileImageData = null,
        initials = name.initials(),
        options = AvatarOptions(size = size, fontSize = (size.value / 3).sp),
    )
}
