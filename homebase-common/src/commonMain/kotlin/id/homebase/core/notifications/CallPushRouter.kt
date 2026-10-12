package id.homebase.core.notifications

import id.homebase.api.serialization.OdinSystemSerializer

/**
 * Seam between the push entry points (FCM service, PushKit, the shared notification funnel) and the
 * call stack in homebase-chat, which this module cannot depend on. A ring and a hangup are told apart
 * by typeId because the delivered payload carries no `kind`; tagId is the callId for both.
 */
object CallPushRouter {
    const val RING_TYPE_ID = "6f0d2b8e-3c41-4a7f-9b52-d1e8a4c7f603"
    const val HANGUP_TYPE_ID = "b94e7a15-d2f8-4c60-a381-5e0c9d7b2f48"

    interface Handler {
        fun onRing(callId: String, from: String, message: String?)
        fun onHangup(callId: String, from: String)
    }

    @kotlin.concurrent.Volatile
    var handler: Handler? = null

    /** Parses an FCM/VoIP data map; returns true when it was a call push (and so needs no chat notification). */
    fun tryHandle(data: Map<String, String>): Boolean {
        val raw = data["data"] ?: return false
        val notification = runCatching { OdinSystemSerializer.deserialize<PushNotification>(raw) }.getOrNull() ?: return false
        return tryHandle(notification)
    }

    fun tryHandle(notification: PushNotification): Boolean {
        val options = notification.options
        val target = handler
        return when (options.typeId.lowercase()) {
            RING_TYPE_ID -> { target?.onRing(options.tagId, notification.senderId, options.unEncryptedMessage); true }
            HANGUP_TYPE_ID -> { target?.onHangup(options.tagId, notification.senderId); true }
            else -> false
        }
    }
}
