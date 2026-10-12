package id.homebase.chat.calling

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.homebase.chat.services.calling.AudioRoute
import id.homebase.chat.services.calling.CallController
import id.homebase.chat.services.calling.CallDirection
import id.homebase.chat.services.calling.CallEndReason
import id.homebase.chat.services.calling.CallEngineFactory
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.CallQuality
import id.homebase.chat.services.calling.CallVideos
import id.homebase.chat.services.calling.FailReason
import id.homebase.chat.services.calling.FakeCallEngine
import id.homebase.chat.services.calling.FakeCallScript
import id.homebase.chat.services.calling.FakeVideo
import id.homebase.chat.services.calling.LoopbackSignaling
import id.homebase.chat.services.calling.RING_TIMEOUT_MS
import id.homebase.chat.services.calling.platformCallEngineFactory
import id.homebase.core.util.initials
import id.homebase.resources.MR
import id.homebase.resources.call_ended_declined
import id.homebase.resources.call_ended_hangup
import id.homebase.resources.call_failed_title
import id.homebase.resources.call_lab_busy
import id.homebase.resources.call_lab_connected_video
import id.homebase.resources.call_lab_connected_voice
import id.homebase.resources.call_lab_controls
import id.homebase.resources.call_lab_drop
import id.homebase.resources.call_lab_fail
import id.homebase.resources.call_lab_glare
import id.homebase.resources.call_lab_incoming_video
import id.homebase.resources.call_lab_incoming_voice
import id.homebase.resources.call_lab_intro
import id.homebase.resources.call_lab_network_back
import id.homebase.resources.call_lab_network_drop
import id.homebase.resources.call_lab_outgoing_video
import id.homebase.resources.call_lab_outgoing_voice
import id.homebase.resources.call_lab_real
import id.homebase.resources.call_lab_remote_accepts
import id.homebase.resources.call_lab_remote_hangs_up
import id.homebase.resources.call_lab_ring_timeout
import id.homebase.resources.call_lab_scenarios
import id.homebase.resources.call_lab_screens
import id.homebase.resources.call_lab_live
import id.homebase.resources.call_lab_stop
import id.homebase.resources.call_lab_title
import id.homebase.resources.call_permission_title
import id.homebase.resources.call_poor_connection
import id.homebase.resources.call_status_calling
import id.homebase.resources.call_status_connecting
import id.homebase.resources.call_status_reconnecting
import id.homebase.resources.call_status_ringing
import id.homebase.resources.menu_back
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private const val ME = "frodo.baggins.demo.rocks"
private const val PEER = "samwise.gamgee.demo.rocks"
private const val OTHER = "gandalf.the.grey.demo.rocks"
private const val LAB_RING_TIMEOUT_MS = 4_000L
private val PeerName = PEER.substringBefore('.').replaceFirstChar { it.uppercase() }

private class Lab(
    val scope: CoroutineScope,
    script: FakeCallScript,
    real: CallEngineFactory? = null,
    ringTimeoutMs: Long = RING_TIMEOUT_MS,
) {
    val net = LoopbackSignaling()

    @Volatile
    var myEngine: FakeCallEngine? = null
    val me = CallController(
        ME,
        net.endpoint(ME),
        real ?: CallEngineFactory { _ -> FakeCallEngine(scope, script, PEER, ME).also { myEngine = it } },
        scope,
        ringTimeoutMs = ringTimeoutMs,
    )
    val peer = CallController(PEER, net.endpoint(PEER), real ?: CallEngineFactory { _ -> FakeCallEngine(scope, FakeCallScript(), ME, PEER) }, scope)

    fun close() {
        me.close()
        peer.close()
        scope.cancel()
    }
}

private class LabAction(val label: StringResource, val enabled: Boolean = true, val onClick: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CallLabScreen(onBackClick: () -> Unit) {
    var lab by remember { mutableStateOf<Lab?>(null) }
    var preview by remember { mutableStateOf<CallUiState?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { lab?.close() } }

    val gate = rememberCallPermissionGate()
    fun launch(
        script: FakeCallScript = FakeCallScript(),
        real: CallEngineFactory? = null,
        ringTimeoutMs: Long = RING_TIMEOUT_MS,
        block: suspend Lab.() -> Unit,
    ) {
        lab?.close()
        preview = null
        val next = Lab(CoroutineScope(SupervisorJob() + Dispatchers.Default), script, real, ringTimeoutMs)
        lab = next
        scope.launch { next.block() }
    }

    val active = lab
    val scenarios = buildList {
        add(LabAction(MR.string.call_lab_incoming_voice) { launch { peer.startCall(ME, video = false) } })
        add(LabAction(MR.string.call_lab_incoming_video) { launch(FakeCallScript(remoteVideoOnConnect = true)) { peer.startCall(ME, video = true) } })
        add(LabAction(MR.string.call_lab_outgoing_voice) { launch { me.startCall(PEER, false) } })
        add(LabAction(MR.string.call_lab_outgoing_video) { launch(FakeCallScript(remoteVideoOnConnect = true)) { me.startCall(PEER, true) } })
        add(
            LabAction(MR.string.call_lab_drop) {
                launch(FakeCallScript(connectAfterMs = 800, dropAtMs = 4000, recoverAfterDropMs = 6000)) {
                    me.startCall(PEER, false); delay(1500); peer.accept()
                }
            },
        )
        add(LabAction(MR.string.call_lab_fail) { launch(FakeCallScript(failConnect = true)) { me.startCall(PEER, false); delay(1500); peer.accept() } })
        add(LabAction(MR.string.call_lab_busy) { launch { peer.startCall(OTHER, false); delay(300); me.startCall(PEER, false) } })
        add(LabAction(MR.string.call_lab_ring_timeout) { launch(ringTimeoutMs = LAB_RING_TIMEOUT_MS) { me.startCall(PEER, false) } })
        add(LabAction(MR.string.call_lab_glare) { launch { me.startCall(PEER, false); peer.startCall(ME, false) } })
        platformCallEngineFactory()?.let { factory ->
            add(
                LabAction(MR.string.call_lab_real) {
                    gate.run(video = false) { launch(real = factory) { me.startCall(PEER, false); delay(1500); peer.accept() } }
                },
            )
        }
    }
    val live = listOf(
        LabAction(MR.string.call_lab_remote_accepts, active != null) { active?.peer?.accept() },
        LabAction(MR.string.call_lab_network_drop, active != null) { active?.myEngine?.simulateDrop() },
        LabAction(MR.string.call_lab_network_back, active != null) { active?.myEngine?.simulateRecover() },
        LabAction(MR.string.call_lab_remote_hangs_up, active != null) { active?.peer?.hangup() },
        LabAction(MR.string.call_lab_stop, active != null || preview != null) { lab?.close(); lab = null; preview = null },
    )
    val states = remember { labPreviewStates() }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(MR.string.call_lab_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(MR.string.menu_back))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.padding(padding).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(MR.string.call_lab_intro), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(MR.string.call_lab_scenarios), style = MaterialTheme.typography.titleSmall)
                scenarios.forEach { LabButton(it) }
                Text(stringResource(MR.string.call_lab_live), style = MaterialTheme.typography.titleSmall)
                live.forEach { LabButton(it) }
                Text(stringResource(MR.string.call_lab_screens), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    states.forEach { (label, state) ->
                        AssistChip(
                            onClick = { lab?.close(); lab = null; preview = state },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
            }
        }
        val shown = preview
        if (active != null) {
            key(active) { LiveCallLayer(active) }
        } else if (shown != null) {
            PreviewLayer(shown, onChange = { preview = it })
        }
        if (active != null || shown != null) {
            LabPanel(scenarios + live, Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
private fun LiveCallLayer(lab: Lab) {
    val viewModel = viewModel(key = lab.toString()) {
        CallViewModel(lab.me, audioRoutes = { listOf(AudioRoute.Earpiece, AudioRoute.Speaker, AudioRoute.Bluetooth) })
    }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    CallOverlay(viewModel, ui, PeerName, avatar = { size -> LabAvatar(size) })
}

@Composable
private fun PreviewLayer(state: CallUiState, onChange: (CallUiState?) -> Unit) {
    val actions = remember(state) {
        CallScreenActions(
            acceptAudio = { onChange(state.copy(phase = CallPhase.Connected, localVideo = false, connectedAtMs = Clock.System.now().toEpochMilliseconds())) },
            acceptVideo = { onChange(state.copy(phase = CallPhase.Connected, connectedAtMs = Clock.System.now().toEpochMilliseconds())) },
            decline = { onChange(null) },
            hangup = { onChange(null) },
            toggleMute = { onChange(state.copy(muted = !state.muted)) },
            toggleVideo = { onChange(state.copy(localVideo = !state.localVideo)) },
            switchCamera = {},
            setRoute = { onChange(state.copy(route = it)) },
            onVerificationClick = {},
            retry = { onChange(state.copy(phase = CallPhase.Outgoing)) },
            close = { onChange(null) },
            minimize = {},
            endFromKeyboard = { onChange(null) },
        )
    }
    CallScreen(ui = state, peerName = PeerName, avatar = { size -> LabAvatar(size) }, actions = actions)
}

@Composable
private fun LabAvatar(size: Dp) {
    Box(
        Modifier.size(size).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(PEER.replace('.', ' ').initials(), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun LabPanel(actions: List<LabAction>, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(MR.string.call_lab_controls)
    Box(modifier.padding(end = 8.dp)) {
        if (open) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 8.dp,
                modifier = Modifier.widthIn(max = 260.dp).heightIn(max = 480.dp),
            ) {
                Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = { open = false }, modifier = Modifier.align(Alignment.End)) {
                        Icon(Icons.Default.Close, description)
                    }
                    actions.forEach { LabButton(it) }
                }
            }
        } else {
            Surface(
                onClick = { open = true },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Tune, description) }
            }
        }
    }
}

@Composable
private fun LabButton(action: LabAction) {
    FilledTonalButton(onClick = action.onClick, enabled = action.enabled, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(action.label))
    }
}

private fun labPreviewStates(): List<Pair<StringResource, CallUiState>> {
    val connectedAt = Clock.System.now().toEpochMilliseconds() - 83_000
    val voice = CallUiState(callId = "lab", peer = PEER, availableRoutes = listOf(AudioRoute.Earpiece, AudioRoute.Speaker, AudioRoute.Bluetooth))
    val video = voice.copy(
        localVideo = true,
        remoteVideo = true,
        videos = CallVideos(FakeVideo(0xFF5C6BC0, "FB"), FakeVideo(0xFF26A69A, "SG")),
    )
    val connected = voice.copy(phase = CallPhase.Connected, direction = CallDirection.Outgoing, connectedAtMs = connectedAt, verificationCode = "123 456")
    val connectedVideo = video.copy(phase = CallPhase.Connected, direction = CallDirection.Outgoing, connectedAtMs = connectedAt, verificationCode = "123 456")
    return listOf(
        MR.string.call_status_calling to voice.copy(phase = CallPhase.Outgoing, direction = CallDirection.Outgoing),
        MR.string.call_status_ringing to voice.copy(phase = CallPhase.Outgoing, direction = CallDirection.Outgoing, peerRinging = true),
        MR.string.call_lab_incoming_voice to voice.copy(phase = CallPhase.Incoming, direction = CallDirection.Incoming),
        MR.string.call_lab_incoming_video to video.copy(phase = CallPhase.Incoming, direction = CallDirection.Incoming),
        MR.string.call_status_connecting to voice.copy(phase = CallPhase.Connecting),
        MR.string.call_lab_connected_voice to connected,
        MR.string.call_lab_connected_video to connectedVideo,
        MR.string.call_poor_connection to connected.copy(quality = CallQuality.Poor),
        MR.string.call_status_reconnecting to connected.copy(phase = CallPhase.Reconnecting),
        MR.string.call_failed_title to voice.copy(phase = CallPhase.Ended(CallEndReason.Failed(FailReason.NoRoute))),
        MR.string.call_permission_title to voice.copy(phase = CallPhase.Ended(CallEndReason.Failed(FailReason.Permission))),
        MR.string.call_ended_hangup to connected.copy(phase = CallPhase.Ended(CallEndReason.Hangup(remote = true))),
        MR.string.call_ended_declined to voice.copy(phase = CallPhase.Ended(CallEndReason.Declined)),
    )
}
