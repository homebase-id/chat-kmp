package id.homebase.core.notifications

import id.homebase.api.serialization.OdinSystemSerializer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

fun interface PushInterceptor {
    fun handle(notification: PushNotification)
}

/**
 * Features that must see a push before the chat notification path (calls: a ringing phone cannot wait for a WorkManager hop)
 * register the typeIds they own; every push entry point asks [tryHandle] once and stops when it returns true.
 */
object PushInterceptors {
    private val byTypeId = MutableStateFlow<Map<String, PushInterceptor>>(emptyMap())

    fun register(typeIds: Collection<String>, interceptor: PushInterceptor) =
        byTypeId.update { it + typeIds.associate { id -> id.lowercase() to interceptor } }

    fun unregister(typeIds: Collection<String>) = byTypeId.update { it - typeIds.map { id -> id.lowercase() }.toSet() }

    /** Parses an FCM/VoIP data map; true when an interceptor took the push (and so it needs no chat notification). */
    fun tryHandle(data: Map<String, String>): Boolean {
        val raw = data["data"] ?: return false
        val notification = runCatching { OdinSystemSerializer.deserialize<PushNotification>(raw) }.getOrNull() ?: return false
        return tryHandle(notification)
    }

    fun tryHandle(notification: PushNotification): Boolean {
        val interceptor = byTypeId.value[notification.options.typeId.lowercase()] ?: return false
        interceptor.handle(notification)
        return true
    }
}
