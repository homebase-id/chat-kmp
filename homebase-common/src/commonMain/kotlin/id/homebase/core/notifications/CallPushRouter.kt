package id.homebase.core.notifications

/**
 * Seam between the push entry points and the call stack in homebase-chat, which this module cannot depend on. A ring and a
 * hangup are told apart by typeId because the delivered payload carries no `kind`; tagId is the callId for both.
 * Setting [handler] registers both typeIds with [PushInterceptors]; clearing it unregisters them.
 */
object CallPushRouter {
    const val RING_TYPE_ID = "6f0d2b8e-3c41-4a7f-9b52-d1e8a4c7f603"
    const val HANGUP_TYPE_ID = "b94e7a15-d2f8-4c60-a381-5e0c9d7b2f48"

    private val typeIds = listOf(RING_TYPE_ID, HANGUP_TYPE_ID)

    interface Handler {
        /** [sentAtMs] is the server's send time, 0 when the payload carries none. */
        fun onRing(callId: String, from: String, sentAtMs: Long)
        fun onHangup(callId: String, from: String)
    }

    var handler: Handler? = null
        set(value) {
            field = value
            if (value == null) {
                PushInterceptors.unregister(typeIds)
            } else {
                PushInterceptors.register(typeIds) { notification ->
                    val options = notification.options
                    if (options.typeId.equals(RING_TYPE_ID, ignoreCase = true)) {
                        value.onRing(options.tagId, notification.senderId, notification.created)
                    } else {
                        value.onHangup(options.tagId, notification.senderId)
                    }
                }
            }
        }
}
