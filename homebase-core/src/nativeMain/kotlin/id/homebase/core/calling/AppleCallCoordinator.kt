package id.homebase.core.calling

import id.homebase.api.client.contacts.ContactInfoGateway
import id.homebase.api.common.OdinId
import id.homebase.chat.services.calling.CallKitMirror
import id.homebase.chat.services.calling.CallService
import id.homebase.chat.services.calling.CallKitActionsAdapter
import id.homebase.chat.services.calling.CallKitRingRouter
import id.homebase.chat.services.calling.CallKitRingSink
import id.homebase.chat.services.calling.CallRingCoordinator
import id.homebase.chat.services.calling.RING_TIMEOUT_MS
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
    val koin = KoinPlatformTools.defaultContext().get()
    val service = koin.get<CallService>()
    if (!service.available) return

    CallPushRouter.handler = CallKitRingRouter(service, { CallKitBridgeHolder.bridge }, scope)
    val contacts = koin.get<ContactInfoGateway>()
    CallRingCoordinator(
        target = service,
        transitions = service.transitions,
        sink = CallKitRingSink { CallKitBridgeHolder.bridge },
        labelFor = { contacts.displayName(OdinId(it)) },
        scope = scope,
        // CallKit takes a new name at any point while it rings, and a cold PushKit wake resolves contacts slowly.
        labelTimeoutMs = RING_TIMEOUT_MS,
    ).start()
    CallKitBridgeHolder.actions = CallKitActionsAdapter(service)

    scope.launch { CallKitMirror(service) { CallKitBridgeHolder.bridge }.run() }
}
