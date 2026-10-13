package id.homebase.chat.services.calling

import id.homebase.core.notifications.CallNotificationActions
import id.homebase.core.notifications.CallNotifier
import id.homebase.core.notifications.RichNotificationData
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class DesktopCallRingSink(
    private val scope: CoroutineScope,
    private val notifier: CallNotifier,
    private val focusCallWindow: () -> Unit,
    private val tone: RingTonePlayer,
    private val incomingCallText: suspend () -> String,
    private val acceptLabel: suspend () -> String,
    private val declineLabel: suspend () -> String,
    private val onAccept: (callId: String) -> Unit,
    private val onDecline: (callId: String) -> Unit,
    private val appInBackground: () -> Boolean = { false },
    private val missedNotification: suspend (peerId: String, label: String) -> RichNotificationData? = { _, _ -> null },
    private val showMissed: (RichNotificationData) -> Unit = {},
) : CallRingSink {
    private val ringing = ConcurrentHashMap.newKeySet<String>()

    override fun ring(callId: String, label: String) {
        val first = ringing.add(callId)
        scope.launch {
            val body = incomingCallText()
            if (callId !in ringing) return@launch
            notifier.showIncoming(
                callId, label, body,
                CallNotificationActions(
                    acceptLabel = acceptLabel(),
                    declineLabel = declineLabel(),
                    onAccept = { onAccept(callId) },
                    onDecline = { onDecline(callId) },
                    onOpen = focusCallWindow,
                ),
            )
        }
        if (!first) return
        focusCallWindow()
        tone.start()
    }

    override fun stopRinging(callId: String) {
        if (!ringing.remove(callId)) return
        tone.stop()
        notifier.retract(callId)
    }

    override fun missed(peerId: String, label: String) {
        if (!appInBackground()) return
        scope.launch { missedNotification(peerId, label)?.let(showMissed) }
    }

    override fun connected(callId: String) = Unit
    override fun ended(callId: String) = stopRinging(callId)
    override fun setLive(live: Boolean, video: Boolean, callId: String?) = Unit
}
