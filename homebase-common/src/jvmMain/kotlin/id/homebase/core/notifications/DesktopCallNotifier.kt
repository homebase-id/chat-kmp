package id.homebase.core.notifications

import com.mmk.kmpnotifier.notification.NotifierManager

class CallNotificationActions(
    val acceptLabel: String,
    val declineLabel: String,
    val onAccept: () -> Unit,
    val onDecline: () -> Unit,
    val onOpen: () -> Unit,
)

interface CallNotifier {
    fun showIncoming(callId: String, title: String, body: String, actions: CallNotificationActions)
    fun retract(callId: String)
}

/** Nucleus with Accept and Decline buttons where they are known to fire; KMPNotifier without buttons otherwise. */
class DesktopCallNotifier : CallNotifier {
    private val disabled = isMacOsRunningUnbundled()
    private val nucleus: NucleusNotificationAdapter? = if (disabled) null else NucleusNotificationAdapter.createOrNull()

    override fun showIncoming(callId: String, title: String, body: String, actions: CallNotificationActions) {
        if (disabled) return
        val id = callNotificationId(callId)
        val adapter = nucleus
        if (adapter != null) {
            adapter.showCall(id, title, body, actions, withButtons = !isMacOs())
            return
        }
        NotifierManager.getLocalNotifier().notify(id = id, title = title, body = body, payloadData = emptyMap())
    }

    override fun retract(callId: String) {
        if (disabled) return
        val id = callNotificationId(callId)
        val adapter = nucleus
        if (adapter != null) adapter.dismiss(id) else runCatching { NotifierManager.getLocalNotifier().remove(id) }
    }

    private fun isMacOs() = System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)
}

fun callNotificationId(callId: String): Int = "call:$callId".hashCode()
