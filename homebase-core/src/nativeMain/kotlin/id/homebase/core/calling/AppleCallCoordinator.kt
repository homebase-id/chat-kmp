package id.homebase.core.calling

import id.homebase.chat.services.calling.CallKitMirror
import id.homebase.chat.services.calling.CallService
import id.homebase.chat.services.calling.CallKitActionsAdapter
import id.homebase.chat.services.calling.CallKitRingRouter
import id.homebase.core.notifications.NotificationEntry
import id.homebase.core.notifications.VoipTokenRelay
import id.homebase.core.notifications.CallKitBridgeHolder
import id.homebase.core.notifications.CallPushRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatformTools

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * iOS glue. PushKit/CallKit live in Swift (iosApp/CallKitManager.swift), which reports an incoming
 * ring to CallKit synchronously and then feeds the same push into [CallPushRouter]; this side routes it
 * into the single CallService and mirrors outgoing/connected/ended state back to CallKit.
 */
fun startAppleCallCoordinator() {
    VoipTokenRelay.attach { NotificationEntry.fromKoin().onVoipToken(it) }
    val service = KoinPlatformTools.defaultContext().get().get<CallService>()
    if (!service.available) return

    CallPushRouter.handler = CallKitRingRouter(service, { CallKitBridgeHolder.bridge }, scope)
    CallKitBridgeHolder.actions = CallKitActionsAdapter(service)

    scope.launch { CallKitMirror(service) { CallKitBridgeHolder.bridge }.run() }
}
