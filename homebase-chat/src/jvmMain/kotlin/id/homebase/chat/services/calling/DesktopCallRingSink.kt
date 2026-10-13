package id.homebase.chat.services.calling

import id.homebase.core.notifications.CallNotificationActions
import id.homebase.core.notifications.CallNotifier
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class DesktopCallRingSink(
    private val scope: CoroutineScope,
    private val notifier: CallNotifier,
    private val focusCallWindow: () -> Unit,
    private val revealRing: () -> Unit = focusCallWindow,
    private val tone: RingTonePlayer,
    private val incomingCallText: suspend () -> String,
    private val acceptLabel: suspend () -> String,
    private val declineLabel: suspend () -> String,
    private val onAccept: (callId: String) -> Unit,
    private val onDecline: (callId: String) -> Unit,
) : CallRingSink {
    private val ringing = ConcurrentHashMap.newKeySet<String>()
    private val lock = Any()

    override fun ring(callId: String, label: String) {
        val first = ringing.add(callId)
        scope.launch {
            val body = incomingCallText()
            val actions = CallNotificationActions(
                acceptLabel = acceptLabel(),
                declineLabel = declineLabel(),
                onAccept = { onAccept(callId) },
                onDecline = { onDecline(callId) },
                onOpen = focusCallWindow,
            )
            synchronized(lock) {
                if (callId in ringing) notifier.showIncoming(callId, label, body, actions)
            }
        }
        if (!first) return
        revealRing()
        tone.start()
    }

    override fun stopRinging(callId: String) {
        synchronized(lock) {
            if (!ringing.remove(callId)) return
            if (ringing.isEmpty()) tone.stop()
            notifier.retract(callId)
        }
    }

    override fun connected(callId: String) = Unit
    override fun ended(callId: String) = stopRinging(callId)
    override fun setLive(live: Boolean, video: Boolean, callId: String?) = Unit
}
