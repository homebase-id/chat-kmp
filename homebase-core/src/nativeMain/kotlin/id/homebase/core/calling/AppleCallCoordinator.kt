package id.homebase.core.calling

import id.homebase.chat.services.calling.CallDirection
import id.homebase.chat.services.calling.CallEndReason
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.CallService
import id.homebase.core.notifications.CallKitActions
import id.homebase.core.notifications.CallKitBridgeHolder
import id.homebase.core.notifications.CallPushRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatformTools

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * iOS glue. PushKit/CallKit live in Swift (iosApp/CallKitManager.swift), which reports an incoming
 * ring to CallKit synchronously and then feeds the same push into [CallPushRouter]; this side routes it
 * into the single CallService and mirrors outgoing/connected/ended state back to CallKit.
 */
fun startAppleCallCoordinator() {
    val service = KoinPlatformTools.defaultContext().get().get<CallService>()
    if (!service.available) return

    CallPushRouter.handler = object : CallPushRouter.Handler {
        override fun onRing(callId: String, from: String, message: String?) {
            service.onPushRing(callId, from)
        }

        override fun onHangup(callId: String, from: String) {
            service.onPushHangup(callId)
        }
    }
    CallKitBridgeHolder.actions = object : CallKitActions {
        override fun accept(callId: String) {
            service.accept()
        }

        override fun endOrDecline(callId: String) {
            if (service.state.value.phase == CallPhase.Incoming) service.decline() else service.hangup()
        }

        override fun setMuted(muted: Boolean) {
            service.setMuted(muted)
        }
    }

    scope.launch {
        var reported: String? = null
        service.state.map { Triple(it.callId, it.phase, it.direction) }.distinctUntilChanged().collect { (callId, phase, direction) ->
            val bridge = CallKitBridgeHolder.bridge ?: return@collect
            val id = callId ?: return@collect
            val state = service.state.value
            when (phase) {
                CallPhase.Outgoing -> if (reported != id) {
                    reported = id
                    bridge.reportOutgoing(id, state.peer.orEmpty(), state.media.localVideo)
                }
                CallPhase.Connected -> bridge.reportConnected(id)
                is CallPhase.Ended -> {
                    bridge.reportEnded(id, remoteEnded = direction == CallDirection.Incoming || phase.reason is CallEndReason.Hangup)
                    if (reported == id) reported = null
                }
                else -> Unit
            }
        }
    }
}
