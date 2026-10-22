package id.homebase.api.client.websockets

import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class StunUrlStoreTest {
    @Test
    fun handshakeReplyCarriesStunUrlsAtTheTopLevel() {
        val json = """{"notificationType":"deviceHandshakeSuccess","notificationTypeId":"0000",
            "stunUrls":["stun:a.example:3478","stun:b.example:3478"]}"""
        val parsed = OdinSystemSerializer.deserialize<ClientNotificationPayload>(json)
        assertEquals(ClientNotificationType.deviceHandshakeSuccess, parsed.notificationType)
        assertEquals(listOf("stun:a.example:3478", "stun:b.example:3478"), parsed.stunUrls)
    }

    @Test
    fun handshakeSampleFromTheServerDocsKeepsEveryUrlAsGivenAndIgnoresUnknownFields() {
        val json = """{ "notificationType": "deviceHandshakeSuccess", "notificationTypeId": "00000000-0000-0000-0000-000000000000",
            "data": "", "future": {"x": 1},
            "stunUrls": ["stun:stun1-1.eu.ravenhosting.cloud:3478", "stun:stun1-2.eu.ravenhosting.cloud:3478"] }"""
        val parsed = OdinSystemSerializer.deserialize<ClientNotificationPayload>(json)
        assertEquals(ClientNotificationType.deviceHandshakeSuccess, parsed.notificationType)
        assertEquals(
            listOf("stun:stun1-1.eu.ravenhosting.cloud:3478", "stun:stun1-2.eu.ravenhosting.cloud:3478"),
            parsed.stunUrls,
        )
    }

    @Test
    fun handshakeWithoutStunUrlsParsesToEmpty() {
        val parsed = OdinSystemSerializer.deserialize<ClientNotificationPayload>(
            """{"notificationType":"deviceHandshakeSuccess"}""",
        )
        assertEquals(emptyList(), parsed.stunUrls)
    }

    @Test
    fun storeKeepsTheLatestListAsGiven() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        val store = StunUrlStore(bus, MutableStateFlow("frodo.example"), TestScope(UnconfinedTestDispatcher(testScheduler)))
        assertEquals(emptyList(), store.urls.value)
        bus.emit(BackendEvent.StunUrlsReceived(listOf("stun:one:3478", "stun:two:3478")))
        assertEquals(listOf("stun:one:3478", "stun:two:3478"), store.urls.value)
        bus.emit(BackendEvent.StunUrlsReceived(listOf("stun:three:3478")))
        assertEquals(listOf("stun:three:3478"), store.urls.value)
    }

    @Test
    fun signingOutOrSwitchingIdentityForgetsTheUrls() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus()
        val identity = MutableStateFlow<String?>("frodo.example")
        val store = StunUrlStore(bus, identity, TestScope(UnconfinedTestDispatcher(testScheduler)))
        bus.emit(BackendEvent.StunUrlsReceived(listOf("stun:frodo:3478")))
        identity.value = null
        assertEquals(emptyList(), store.urls.value)
        bus.emit(BackendEvent.StunUrlsReceived(listOf("stun:sam:3478")))
        identity.value = "sam.example"
        assertEquals(emptyList(), store.urls.value)
    }
}
