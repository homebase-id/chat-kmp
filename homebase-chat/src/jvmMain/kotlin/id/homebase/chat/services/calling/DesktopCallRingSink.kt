package id.homebase.chat.services.calling

import id.homebase.core.notifications.RichNotificationData
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Desktop ring: a system notification, the window brought to the front, and a repeating sound until the call stops ringing. */
class DesktopCallRingSink(
    private val scope: CoroutineScope,
    private val notify: (RichNotificationData) -> Unit,
    private val bringToFront: () -> Unit,
    private val ringTone: () -> Unit,
    private val incomingCallText: suspend () -> String,
    private val ringIntervalMs: Long = 2_000,
) : CallRingSink {
    private val tones = mutableMapOf<String, Job>()

    override fun ring(callId: String, label: String) {
        val first = callId !in tones
        scope.launch { notify(notification(callId, label, incomingCallText())) }
        if (!first) return
        bringToFront()
        tones[callId] = scope.launch {
            while (true) {
                ringTone()
                delay(ringIntervalMs)
            }
        }
    }

    override fun stopRinging(callId: String) {
        tones.remove(callId)?.cancel()
    }

    override fun connected(callId: String) = Unit
    override fun ended(callId: String) = stopRinging(callId)
    override fun setLive(live: Boolean, video: Boolean, callId: String?) = Unit

    private fun notification(callId: String, label: String, body: String) = RichNotificationData(
        notificationId = callId.hashCode(),
        channelId = "messages",
        conversationId = null,
        title = label,
        body = body,
        senderName = label,
        senderId = "",
        senderImageBytes = null,
        timestamp = Clock.System.now().toEpochMilliseconds(),
        payloadData = emptyMap(),
    )
}
