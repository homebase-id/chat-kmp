package id.homebase.core.calling

import id.homebase.api.browser.DesktopAppFocusManager
import id.homebase.api.browser.WindowKeys
import id.homebase.api.client.contacts.ContactInfoGateway
import id.homebase.api.common.OdinId
import id.homebase.chat.services.calling.CallRingCoordinator
import id.homebase.chat.services.calling.CallService
import id.homebase.chat.services.calling.DesktopCallRingSink
import id.homebase.core.localization.TranslationUtil
import id.homebase.core.notifications.RichNotificationDisplayer
import id.homebase.resources.MR
import id.homebase.resources.call_incoming_generic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.koin.mp.KoinPlatformTools

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

fun startDesktopCallCoordinator() {
    val koin = KoinPlatformTools.defaultContext().get()
    val service = koin.get<CallService>()
    if (!service.available) return
    val contacts = koin.get<ContactInfoGateway>()
    val displayer = RichNotificationDisplayer()
    CallRingCoordinator(
        target = service,
        state = service.state,
        sink = DesktopCallRingSink(
            scope = scope,
            notify = displayer::show,
            bringToFront = { DesktopAppFocusManager.requestFocus("incoming-call", WindowKeys.CALL) },
            ringTone = { java.awt.Toolkit.getDefaultToolkit().beep() },
            incomingCallText = { TranslationUtil.getString(MR.string.call_incoming_generic) },
        ),
        labelFor = { contacts.displayName(OdinId(it)) },
        scope = scope,
    ).start()
}

/** Blocks the quitting thread until any call is hung up and its camera and mic are released. */
fun endDesktopCallForQuit() {
    val service = KoinPlatformTools.defaultContext().getOrNull()?.getOrNull<CallService>() ?: return
    runBlocking { service.endForQuit() }
}
