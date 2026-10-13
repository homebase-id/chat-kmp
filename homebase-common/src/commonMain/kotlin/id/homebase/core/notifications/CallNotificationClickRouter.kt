package id.homebase.core.notifications

import kotlin.concurrent.Volatile

/** Lets a click on a button-less call notification reach the ring window instead of the chat router. */
object CallNotificationClickRouter {
    private const val KEY = "homebase_call_open"

    @Volatile
    var onOpen: (() -> Unit)? = null

    fun payload(callId: String): Map<String, String> = mapOf(KEY to callId)

    fun tryHandle(data: PayloadData): Boolean {
        if (KEY !in data) return false
        onOpen?.invoke()
        return true
    }
}
