package id.homebase.chat.calling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.services.calling.CallController
import id.homebase.chat.services.calling.CallEngineFactory
import id.homebase.chat.services.calling.FakeCallEngine
import id.homebase.chat.services.calling.FakeCallScript
import id.homebase.chat.services.calling.LoopbackSignaling
import id.homebase.chat.services.calling.platformCallEngineFactory
import id.homebase.resources.call_lab_real
import id.homebase.core.util.initials
import id.homebase.resources.MR
import id.homebase.resources.call_lab_busy
import id.homebase.resources.call_lab_drop
import id.homebase.resources.call_lab_fail
import id.homebase.resources.call_lab_incoming_video
import id.homebase.resources.call_lab_incoming_voice
import id.homebase.resources.call_lab_intro
import id.homebase.resources.call_lab_outgoing_video
import id.homebase.resources.call_lab_outgoing_voice
import id.homebase.resources.call_lab_scenarios
import id.homebase.resources.call_lab_stop
import id.homebase.resources.call_lab_title
import id.homebase.resources.menu_back
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private const val ME = "frodo.baggins.demo.rocks"
private const val PEER = "samwise.gamgee.demo.rocks"
private const val OTHER = "gandalf.the.grey.demo.rocks"

private class Lab(val scope: CoroutineScope, script: FakeCallScript, real: CallEngineFactory? = null) {
    val net = LoopbackSignaling()
    val me = CallController(ME, net.endpoint(ME), real ?: CallEngineFactory { _ -> FakeCallEngine(scope, script, PEER, ME) }, scope)
    val peer = CallController(PEER, net.endpoint(PEER), real ?: CallEngineFactory { _ -> FakeCallEngine(scope, FakeCallScript(), ME, PEER) }, scope)
    fun close() {
        me.close()
        peer.close()
        scope.cancel()
    }
}

/** Scripted-peer harness for the real call screens: fake engines over an in-memory loopback, nothing leaves the device. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallLabScreen(onBackClick: () -> Unit) {
    var lab by remember { mutableStateOf<Lab?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { lab?.close() } }

    val gate = rememberCallPermissionGate()
    fun launch(script: FakeCallScript = FakeCallScript(), real: CallEngineFactory? = null, block: suspend Lab.() -> Unit) {
        lab?.close()
        val next = Lab(CoroutineScope(SupervisorJob() + Dispatchers.Default), script, real)
        lab = next
        scope.launch { next.block() }
    }

    val active = lab
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
                LabButton(MR.string.call_lab_incoming_voice) { launch { peer.startCall(ME, video = false) } }
                LabButton(MR.string.call_lab_incoming_video) { launch(FakeCallScript(remoteVideoOnConnect = true)) { peer.startCall(ME, video = true) } }
                LabButton(MR.string.call_lab_outgoing_voice) { launch { me.startCall(PEER, false); delay(2500); peer.accept() } }
                LabButton(MR.string.call_lab_outgoing_video) { launch(FakeCallScript(remoteVideoOnConnect = true)) { me.startCall(PEER, true); delay(2500); peer.accept() } }
                LabButton(MR.string.call_lab_drop) {
                    launch(FakeCallScript(connectAfterMs = 800, dropAtMs = 4000, recoverAfterDropMs = 6000)) {
                        me.startCall(PEER, false); delay(1500); peer.accept()
                    }
                }
                LabButton(MR.string.call_lab_fail) {
                    launch(FakeCallScript(failConnect = true)) { me.startCall(PEER, false); delay(1500); peer.accept() }
                }
                LabButton(MR.string.call_lab_busy) {
                    launch { peer.startCall(OTHER, false); delay(300); me.startCall(PEER, false) }
                }
                platformCallEngineFactory()?.let { factory ->
                    LabButton(MR.string.call_lab_real) {
                        gate.run(video = false) { launch(real = factory) { me.startCall(PEER, false); delay(1500); peer.accept() } }
                    }
                }
                if (active != null) {
                    LabButton(MR.string.call_lab_stop) { lab?.close(); lab = null }
                }
            }
        }
        if (active != null) {
            val state by active.me.state.collectAsStateWithLifecycle()
            val videos by active.me.videos.collectAsStateWithLifecycle()
            val phase = state.phase
            LaunchedEffect(phase is id.homebase.chat.services.calling.CallPhase.Ended) {
                if (phase is id.homebase.chat.services.calling.CallPhase.Ended) {
                    delay(2_500)
                    lab?.close()
                    lab = null
                }
            }
            if (phase != id.homebase.chat.services.calling.CallPhase.Idle) {
                CallScreen(
                    state = state,
                    videos = videos,
                    peerName = PEER.substringBefore('.').replaceFirstChar { it.uppercase() },
                    avatar = { size ->
                        Box(
                            Modifier.size(size).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(PEER.replace('.', ' ').initials(), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    },
                    actions = CallScreenActions(
                        accept = active.me::accept, decline = active.me::decline, hangup = active.me::hangup,
                        setMuted = active.me::setMuted, setVideo = active.me::setVideo,
                        switchCamera = active.me::switchCamera, setRoute = active.me::setAudioRoute,
                        onVerificationClick = {},
                    ),
                )
            }
        }
    }
}

@Composable
private fun LabButton(label: org.jetbrains.compose.resources.StringResource, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(stringResource(label)) }
}
