package id.homebase.api.client.liverelay

import id.homebase.api.client.websockets.LiveRelayReceivedNotification
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveRelayContractTest {

    @Test
    fun liveLocationCodec_roundTrips() {
        val point = LiveLocationPoint(
            lat = 37.42342,
            lon = -122.08395,
            acc = 5.0,
            spd = 1.4,
            hdg = 270.0,
            ts = 1_718_800_000_000L,
        )
        val blob = LiveLocationCodec.encode(point)
        val decoded = LiveLocationCodec.decode(blob)
        assertEquals(point, decoded)
    }

    @Test
    fun liveLocationCodec_roundTrips_withNullExtras() {
        val point = LiveLocationPoint(lat = 0.0, lon = 0.0, ts = 1L)
        assertEquals(point, LiveLocationCodec.decode(LiveLocationCodec.encode(point)))
    }

    @Test
    fun liveLocationCodec_decodesGarbageToNull() {
        assertNull(LiveLocationCodec.decode("not-valid-base64-or-json!!!"))
    }

    /**
     * The HOP-3 `data` payload the server delivers (see the odin-core `LiveRelayShareGpsExampleTest`):
     * `{ senderOdinId, channelKey, blob, receivedAt }`. This is the exact shape
     * `OdinWebSocketClient.dispatchNotification` deserializes before emitting `LiveRelayReceived`.
     */
    @Test
    fun liveRelayReceivedNotification_parsesServerPayload() {
        val blob = LiveLocationCodec.encode(LiveLocationPoint(lat = 1.0, lon = 2.0, ts = 9L))
        val json = """
            {"senderOdinId":"frodo.dotyou.cloud","channelKey":"$LIVE_LOCATION_CHANNEL_KEY",
             "blob":"$blob","receivedAt":1718800000000}
        """.trimIndent()

        val parsed = OdinSystemSerializer.deserialize<LiveRelayReceivedNotification>(json)

        assertEquals("frodo.dotyou.cloud", parsed.senderOdinId.domainName)
        assertEquals(LIVE_LOCATION_CHANNEL_KEY, parsed.channelKey)
        assertEquals(1_718_800_000_000L, parsed.receivedAt)
        val pt = assertNotNull(LiveLocationCodec.decode(parsed.blob))
        assertEquals(1.0, pt.lat)
        assertEquals(2.0, pt.lon)
    }

    @Test
    fun liveRelayRequest_serializesWithoutAppId() {
        val json = OdinSystemSerializer.serialize(
            LiveRelayRequest(
                channelKey = LIVE_LOCATION_CHANNEL_KEY,
                recipients = listOf("sam.dotyou.cloud"),
                blob = "AAAA",
            )
        )
        assertTrue(json.contains("\"channelKey\""))
        assertTrue(json.contains("\"recipients\""))
        assertTrue(json.contains("\"blob\""))
        // AppId is inferred server-side — the client must never send it.
        assertTrue(!json.contains("appId", ignoreCase = true))
    }
}
