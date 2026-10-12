package id.homebase.api.client.websockets

import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Latest STUN URLs from the device's own server's handshake reply; used as given, never built client-side. */
class StunUrlStore(eventBus: EventBus, scope: CoroutineScope) {
    private val _urls = MutableStateFlow<List<String>>(emptyList())
    val urls: StateFlow<List<String>> = _urls.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            eventBus.events.collect { if (it is BackendEvent.StunUrlsReceived) _urls.value = it.urls }
        }
    }
}
