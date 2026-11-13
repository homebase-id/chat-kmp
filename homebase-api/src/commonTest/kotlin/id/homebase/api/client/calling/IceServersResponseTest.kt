package id.homebase.api.client.calling

import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IceServersResponseTest {
    // As odin-core's docs/turn.md shows it, with a host that runs TURN.
    private val withTurn = """
        {"iceServers":[{"urls":["stun:stun1-1.eu.example:3478"]},
                       {"urls":["turn:frodo.example:3479?transport=udp","turn:frodo.example:3479?transport=tcp"],
                        "username":"1760000300:frodo.example","credential":"8L3k"}],
         "expiresAt":1760000300000}
    """.trimIndent()

    @Test
    fun theServersAndTheLoginParseAsOdinCoreSendsThem() {
        val parsed = OdinSystemSerializer.deserialize<IceServersResponse>(withTurn)
        assertEquals(CallIceServer(listOf("stun:stun1-1.eu.example:3478")), parsed.iceServers[0])
        assertEquals(
            CallIceServer(
                listOf("turn:frodo.example:3479?transport=udp", "turn:frodo.example:3479?transport=tcp"),
                username = "1760000300:frodo.example",
                credential = "8L3k",
            ),
            parsed.iceServers[1],
        )
        assertEquals(1_760_000_300_000L, parsed.expiresAt?.milliseconds)
    }

    @Test
    fun aHostWithoutTurnSendsStunAndANullExpiry() {
        val parsed = OdinSystemSerializer.deserialize<IceServersResponse>(
            """{"iceServers":[{"urls":["stun:stun1-1.eu.example:3478"]}],"expiresAt":null}""",
        )
        assertEquals(listOf(CallIceServer(listOf("stun:stun1-1.eu.example:3478"))), parsed.iceServers)
        assertNull(parsed.expiresAt)
        assertNull(parsed.answer(null, 0).loginValidForMs)
    }

    @Test
    fun theLoginsLifetimeIsReadAgainstTheServersClockNotTheDevices() {
        val parsed = OdinSystemSerializer.deserialize<IceServersResponse>(withTurn)
        // Server time 1760000000 s, so 300 s left, whatever the device clock says.
        val serverDate = "Thu, 09 Oct 2025 08:53:20 GMT"
        assertEquals(300_000L, parsed.answer(serverDate, deviceNowMs = 0).loginValidForMs)
        assertEquals(300_000L, parsed.answer(serverDate, deviceNowMs = 1_760_000_200_000).loginValidForMs)
    }

    @Test
    fun withoutADateHeaderTheDeviceClockIsUsed() {
        val parsed = OdinSystemSerializer.deserialize<IceServersResponse>(withTurn)
        assertEquals(100_000L, parsed.answer(null, deviceNowMs = 1_760_000_200_000).loginValidForMs)
        assertEquals(0L, parsed.answer("not a date", deviceNowMs = 1_760_000_400_000).loginValidForMs)
    }

    @Test
    fun theLoginIsNeverPrinted() {
        val text = OdinSystemSerializer.deserialize<IceServersResponse>(withTurn).toString()
        assertEquals(false, "8L3k" in text)
        assertEquals(false, "1760000300:frodo" in text)
    }
}
