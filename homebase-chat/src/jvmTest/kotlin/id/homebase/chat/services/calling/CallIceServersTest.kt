package id.homebase.chat.services.calling

import id.homebase.api.client.calling.CallIceServer
import id.homebase.api.client.calling.IceServersResponse
import id.homebase.api.common.time.UnixTimeUtc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class CallIceServersTest {
    private val frodo = "frodo.baggins.demo.rocks"
    private val sam = "samwise.gamgee.demo.rocks"
    private val stun = CallIceServer(listOf("stun:stun1-1.eu.example:3478"))

    private fun turn(n: Int) = CallIceServer(listOf("turn:stun1-1.eu.example:3479?transport=udp"), "login-$n", "secret-$n")

    private class Side(id: String, scope: TestScope, net: LoopbackSignaling, ice: suspend (peer: String) -> CallIceServers) {
        val engines = mutableListOf<FakeCallEngine>()
        val createdWith = mutableListOf<List<CallIceServer>>()
        val controller = CallController(
            selfOdinId = id,
            signaling = net.endpoint(id),
            engineFactory = CallEngineFactory { servers ->
                createdWith += servers
                FakeCallEngine(scope.backgroundScope).also { engines += it }
            },
            scope = scope.backgroundScope,
            iceServers = ice,
            clock = { scope.testScheduler.currentTime },
        )
        val phase get() = controller.state.value.phase
    }

    // Each request hands out the next login, good for [lifetimeMs] from when it was asked.
    private inner class Logins(private val scope: TestScope, private val lifetimeMs: Long = 300_000, private val latencyMs: Long = 0) {
        val asked = mutableListOf<String>()
        suspend fun get(peer: String): CallIceServers {
            asked += peer
            delay(latencyMs)
            return CallIceServers(listOf(stun, turn(asked.size)), expiresAtMs = scope.testScheduler.currentTime + lifetimeMs)
        }
    }

    private fun TestScope.connect(caller: Logins, callee: Logins): Pair<Side, Side> {
        val net = LoopbackSignaling(now = { testScheduler.currentTime })
        val a = Side(frodo, this, net, caller::get)
        val b = Side(sam, this, net, callee::get)
        a.controller.startCall(sam, video = false)
        runCurrent()
        b.controller.accept()
        advanceTimeBy(1_001)
        assertEquals(CallPhase.Connected, a.phase)
        assertEquals(CallPhase.Connected, b.phase)
        return a to b
    }

    @Test
    fun eachSideGathersWithTheLoginItsOwnServerHandsOutForThatPeer() = runTest {
        val caller = Logins(this)
        val callee = Logins(this)
        val (a, b) = connect(caller, callee)

        assertEquals(listOf(sam), caller.asked)
        assertEquals(listOf(frodo), callee.asked)
        assertEquals(listOf(stun, turn(1)), a.createdWith.single())
        assertEquals(listOf(stun, turn(1)), b.createdWith.single())
    }

    @Test
    fun aRingFetchesTheLoginSoAcceptingDoesNotWaitForIt() = runTest {
        val net = LoopbackSignaling(now = { testScheduler.currentTime })
        val callee = Logins(this, latencyMs = 2_000)
        val a = Side(frodo, this, net, Logins(this)::get)
        val b = Side(sam, this, net, callee::get)
        a.controller.startCall(sam, video = false)
        runCurrent()
        assertEquals(CallPhase.Incoming, b.phase)
        assertEquals(listOf(frodo), callee.asked)

        advanceTimeBy(5_000)
        b.controller.accept()
        runCurrent()
        assertEquals(CallPhase.Connecting, b.phase)
        assertEquals(listOf(stun, turn(1)), b.createdWith.single())
        assertEquals(1, callee.asked.size)
    }

    @Test
    fun aRingAnsweredWhenItsLoginIsAboutToExpireAsksForANewOne() = runTest {
        val net = LoopbackSignaling(now = { testScheduler.currentTime })
        val callee = Logins(this, lifetimeMs = 60_000)
        val a = Side(frodo, this, net, Logins(this)::get)
        val b = Side(sam, this, net, callee::get)
        a.controller.startCall(sam, video = false)
        runCurrent()
        advanceTimeBy(40_000)
        b.controller.accept()
        runCurrent()

        assertEquals(2, callee.asked.size)
        assertEquals(listOf(stun, turn(2)), b.createdWith.single())
    }

    @Test
    fun aReconnectAfterTheLoginsExpiredGathersWithFreshOnesOnBothSides() = runTest {
        val caller = Logins(this)
        val callee = Logins(this)
        val (a, b) = connect(caller, callee)

        advanceTimeBy(10 * 60_000)
        a.engines.single().simulateDrop()
        runCurrent()

        assertEquals(listOf(listOf(stun, turn(2))), a.engines.single().iceServerUpdates)
        assertEquals(listOf(listOf(stun, turn(2))), b.engines.single().iceServerUpdates)
        assertEquals(1, a.engines.single().restarts)
        assertEquals(1, b.engines.single().restarts)
    }

    @Test
    fun aReconnectWhileTheLoginIsFreshAsksForNothing() = runTest {
        val caller = Logins(this)
        val callee = Logins(this)
        val (a, b) = connect(caller, callee)

        a.engines.single().simulateDrop()
        runCurrent()

        assertEquals(1, a.engines.single().restarts)
        assertTrue(a.engines.single().iceServerUpdates.isEmpty())
        assertTrue(b.engines.single().iceServerUpdates.isEmpty())
        assertEquals(1, caller.asked.size)
        assertEquals(1, callee.asked.size)
    }

    @Test
    fun aReconnectWhoseRequestFailsKeepsTheServersTheCallHas() = runTest {
        val net = LoopbackSignaling(now = { testScheduler.currentTime })
        var fail = false
        val logins = Logins(this)
        val a = Side(frodo, this, net) { peer -> if (fail) CallIceServers(listOf(stun), fromServer = false) else logins.get(peer) }
        val b = Side(sam, this, net, Logins(this)::get)
        a.controller.startCall(sam, video = false)
        runCurrent()
        b.controller.accept()
        advanceTimeBy(1_001)

        fail = true
        advanceTimeBy(10 * 60_000)
        a.engines.single().simulateDrop()
        runCurrent()

        assertEquals(1, a.engines.single().restarts)
        assertTrue(a.engines.single().iceServerUpdates.isEmpty())
    }

    @Test
    fun theServersAnswerIsUsedAsGiven() = runTest {
        val source = CallIceServerSource(
            fetch = { IceServersResponse(listOf(stun, turn(1)), UnixTimeUtc(1_000_000)) },
            handshakeStunUrls = { error("not asked") },
        )
        val ice = source.forCall(sam)
        assertEquals(listOf(stun, turn(1)), ice.servers)
        assertEquals(1_000_000L, ice.expiresAtMs)
        assertTrue(ice.fromServer)
    }

    @Test
    fun aServerWithoutTurnGivesStunOnlyWithNoExpiry() = runTest {
        val ice = CallIceServerSource({ IceServersResponse(listOf(stun)) }, { error("not asked") }).forCall(sam)
        assertEquals(listOf(stun), ice.servers)
        assertEquals(null, ice.expiresAtMs)
        assertTrue(ice.fromServer)
    }

    @Test
    fun aFailedRequestFallsBackToTheHandshakeStun() = runTest {
        val ice = CallIceServerSource({ throw IllegalStateException("404") }, { stun.urls }).forCall(sam)
        assertEquals(listOf(stun), ice.servers)
        assertFalse(ice.fromServer)
    }

    @Test
    fun aSlowRequestFallsBackToTheHandshakeStunAfterTheWait() = runTest {
        val ice = CallIceServerSource({ awaitCancellation() }, { stun.urls }, timeoutMs = 3_000).forCall(sam)
        assertEquals(3_000, testScheduler.currentTime)
        assertEquals(listOf(stun), ice.servers)
        assertFalse(ice.fromServer)
    }

    @Test
    fun noEndpointAndNoHandshakeStunGathersHostCandidatesOnly() = runTest {
        val ice = CallIceServerSource(null, { emptyList() }).forCall(sam)
        assertEquals(emptyList(), ice.servers)
    }

    @Test
    fun aLoginNeverShowsInTheLog() {
        val text = CallIceServers(listOf(turn(7)), expiresAtMs = 1).toString() + turn(7).toString()
        assertFalse("secret-7" in text)
        assertFalse("login-7" in text)
    }
}
