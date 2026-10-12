package id.homebase.core.calling

import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.CallKitMirror
import id.homebase.chat.services.calling.CallService
import id.homebase.core.notifications.CallKitActions
import id.homebase.core.notifications.CallKitBridgeHolder
import id.homebase.core.notifications.CallPushRouter
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatformTools

private const val TAG = "AppleCallCoordinator"

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
            Logger.i(TAG) { "CallKit CXEndCallAction call=$callId phase=${service.state.value.phase} current=${service.state.value.callId}" }
            if (service.state.value.phase == CallPhase.Incoming) service.decline() else service.hangup()
        }

        override fun setMuted(muted: Boolean) {
            service.setMuted(muted)
        }
    }

    scope.launch { CallKitMirror(service) { CallKitBridgeHolder.bridge }.run() }
}
