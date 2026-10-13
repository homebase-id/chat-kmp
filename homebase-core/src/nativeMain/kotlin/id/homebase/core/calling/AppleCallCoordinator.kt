package id.homebase.core.calling

import id.homebase.api.client.contacts.ContactInfoGateway
import id.homebase.api.common.OdinId
import id.homebase.chat.services.calling.CallKitMirror
import id.homebase.chat.services.calling.CallPictureInPictureTeardown
import id.homebase.chat.services.calling.CallService
import id.homebase.chat.services.calling.CallKitActionsAdapter
import id.homebase.chat.services.calling.CallKitRingRouter
import id.homebase.core.notifications.NotificationEntry
import id.homebase.core.notifications.VoipTokenRelay
import id.homebase.core.notifications.CallKitBridgeHolder
import id.homebase.core.notifications.CallPictureInPictureBridgeHolder
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

    val router = CallKitRingRouter(service, { CallKitBridgeHolder.bridge }, scope)
    val contacts = koin.get<ContactInfoGateway>()
    CallPushRouter.handler = object : CallPushRouter.Handler by router {
        override fun onRing(callId: String, from: String, sentAtMs: Long) {
            router.onRing(callId, from, sentAtMs)
            scope.launch {
                val label = contacts.displayName(OdinId(from))
                if (!label.isNullOrBlank() && label != from) CallKitBridgeHolder.bridge?.reportCaller(callId, label)
            }
        }
    }
    CallKitBridgeHolder.actions = CallKitActionsAdapter(service)

    scope.launch { CallKitMirror(service) { CallKitBridgeHolder.bridge }.run() }
    scope.launch { CallPictureInPictureTeardown(service.state) { CallPictureInPictureBridgeHolder.bridge }.run() }
}
