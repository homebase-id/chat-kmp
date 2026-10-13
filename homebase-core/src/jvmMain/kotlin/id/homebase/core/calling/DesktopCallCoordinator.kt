package id.homebase.core.calling

import id.homebase.api.browser.DesktopAppFocusManager
import id.homebase.api.browser.WindowKeys
import id.homebase.api.client.contacts.ContactInfoGateway
import id.homebase.api.common.OdinId
import id.homebase.chat.services.calling.CallRingCoordinator
import id.homebase.chat.services.calling.CallService
import id.homebase.chat.services.calling.DesktopCallRingSink
import id.homebase.core.localization.TranslationUtil
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.XorIdUtil
import id.homebase.chat.services.calling.OsRingTonePlayer
import id.homebase.core.config.AppConfig
import id.homebase.core.notifications.DesktopCallNotifier
import id.homebase.core.notifications.PushNotification
import id.homebase.core.notifications.PushNotificationPayloadOptions
import id.homebase.core.notifications.RichNotificationData
import id.homebase.core.notifications.RichNotificationDisplayer
import java.awt.KeyboardFocusManager
import kotlin.time.Clock
import kotlin.uuid.Uuid
import id.homebase.resources.MR
import id.homebase.resources.call_accept
import id.homebase.resources.call_decline
import id.homebase.resources.call_incoming_generic
import id.homebase.resources.call_missed_notification_title
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
    val credentials = koin.get<CredentialsManager>()
    val displayer = RichNotificationDisplayer()
    lateinit var coordinator: CallRingCoordinator
    coordinator = CallRingCoordinator(
        target = service,
        state = service.state,
        sink = DesktopCallRingSink(
            scope = scope,
            notifier = DesktopCallNotifier(),
            focusCallWindow = { DesktopAppFocusManager.requestFocus("incoming-call", WindowKeys.CALL) },
            revealRing = { DesktopAppFocusManager.revealWithoutFocus(WindowKeys.CALL) },
            tone = OsRingTonePlayer(scope),
            incomingCallText = { TranslationUtil.getString(MR.string.call_incoming_generic) },
            acceptLabel = { TranslationUtil.getString(MR.string.call_accept) },
            declineLabel = { TranslationUtil.getString(MR.string.call_decline) },
            onAccept = { coordinator.accept(it) },
            onDecline = { coordinator.decline(it) },
            appInBackground = { KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow == null },
            missedNotification = { peerId, label ->
                val self = credentials.getActiveDomain()?.domainName ?: return@DesktopCallRingSink null
                missedCallNotification(
                    conversationId = XorIdUtil.getNewXorId(self, peerId).toString(),
                    peerId = peerId,
                    title = TranslationUtil.getString(MR.string.call_missed_notification_title, label),
                    appId = AppConfig.APP_ID,
                )
            },
            showMissed = displayer::show,
        ),
        labelFor = { contacts.displayName(OdinId(it)) },
        scope = scope,
    ).also { it.start() }
}

private fun missedCallNotification(conversationId: String, peerId: String, title: String, appId: String): RichNotificationData {
    val now = Clock.System.now().toEpochMilliseconds()
    val push = PushNotification(
        senderId = peerId,
        created = now,
        options = PushNotificationPayloadOptions(appId = Uuid.parse(appId).toString(), typeId = conversationId),
    )
    return RichNotificationData(
        notificationId = conversationId.hashCode(),
        channelId = "messages",
        conversationId = conversationId,
        title = title,
        body = title,
        senderName = peerId,
        senderId = peerId,
        senderImageBytes = null,
        timestamp = now,
        payloadData = mapOf("data" to OdinSystemSerializer.serialize(push)),
    )
}

/** Blocks the quitting thread until any call is hung up and its camera and mic are released. */
fun endDesktopCallForQuit() {
    val service = KoinPlatformTools.defaultContext().getOrNull()?.getOrNull<CallService>() ?: return
    runBlocking { service.endForQuit() }
}
