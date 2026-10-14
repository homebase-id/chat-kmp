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

/** Nucleus with Accept and Decline buttons; KMPNotifier without buttons otherwise, where a click opens the ring window. */
class DesktopCallNotifier : CallNotifier {
    private val disabled = isMacOsRunningUnbundled()
    private val nucleus: NucleusNotificationAdapter? = if (disabled) null else NucleusNotificationAdapter.createOrNull()

    override fun showIncoming(callId: String, title: String, body: String, actions: CallNotificationActions) {
        if (disabled) return
        val id = callNotificationId(callId)
        val adapter = nucleus
        if (adapter != null) {
            adapter.showCall(id, title, body, actions)
            return
        }
        clicks.register(callId, actions.onOpen)
        NotifierManager.getLocalNotifier().notify(id = id, title = title, body = body, payloadData = clicks.payload(callId))
    }

    override fun retract(callId: String) {
        if (disabled) return
        val id = callNotificationId(callId)
        val adapter = nucleus
        if (adapter != null) adapter.dismiss(id) else runCatching { NotifierManager.getLocalNotifier().remove(id) }
        clicks.forget(callId)
    }

    private val clicks by lazy {
        CallNotificationClicks().also { clicks ->
            NotifierManager.addListener(object : NotifierManager.Listener {
                override fun onNotificationClicked(data: com.mmk.kmpnotifier.notification.PayloadData) {
                    clicks.handle(data)
                }
            })
        }
    }
}

/** A button-less call notification's click opens the ring window; the chat tap path ignores it, having no "data" key. */
internal class CallNotificationClicks {
    private val onOpen = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    fun payload(callId: String): Map<String, String> = mapOf(KEY to callId)

    fun register(callId: String, open: () -> Unit) {
        onOpen[callId] = open
    }

    fun forget(callId: String) {
        onOpen.remove(callId)
    }

    fun handle(data: Map<String, Any?>): Boolean {
        val callId = data[KEY] as? String ?: return false
        onOpen[callId]?.invoke()
        return true
    }

    private companion object {
        const val KEY = "homebase_call_open"
    }
}

fun callNotificationId(callId: String): Int = "call:$callId".hashCode()
