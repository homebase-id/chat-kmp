package id.homebase.chat.services.calling

import id.homebase.api.client.calling.CallIceServer
import id.homebase.api.client.calling.IceServersResponse
import id.homebase.api.common.time.UnixTimeUtc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
        val engine get() = engines.single()
    }

    // Each request hands out the next login, good for [lifetimeMs] from when it was asked; [answer] overrides it.
    private class Logins(
        private val scope: TestScope,
        private val stun: CallIceServer,
        private val turn: (Int) -> CallIceServer,
        private val lifetimeMs: Long = 300_000,
        private val latencyMs: Long = 0,
    ) {
        val asked = mutableListOf<String>()
        var answer: ((now: Long) -> CallIceServers)? = null
        suspend fun get(peer: String): CallIceServers {
            asked += peer
            delay(latencyMs)
            val now = scope.testScheduler.currentTime
            return answer?.invoke(now) ?: CallIceServers(listOf(stun, turn(asked.size)), expiresAtMs = now + lifetimeMs, fetchedAtMs = now)
        }
    }

    private fun TestScope.logins(lifetimeMs: Long = 300_000, latencyMs: Long = 0) = Logins(this, stun, ::turn, lifetimeMs, latencyMs)

    // Frodo's call is ringing on Sam's side.
    private fun TestScope.ringing(caller: Logins, callee: Logins): Pair<Side, Side> {
        val net = LoopbackSignaling(now = { testScheduler.currentTime })
        val a = Side(frodo, this, net, caller::get)
        val b = Side(sam, this, net, callee::get)
        a.controller.startCall(sam, video = false)
        runCurrent()
        assertEquals(CallPhase.Incoming, b.phase)
        return a to b
    }

    private fun TestScope.connected(caller: Logins, callee: Logins): Pair<Side, Side> {
        val (a, b) = ringing(caller, callee)
        b.controller.accept()
        advanceTimeBy(1_001)
        assertEquals(CallPhase.Connected, a.phase)
        assertEquals(CallPhase.Connected, b.phase)
        return a to b
    }

    private fun stunOnly(now: Long, fromServer: Boolean) = CallIceServers(listOf(stun), fromServer = fromServer, fetchedAtMs = now)

    @Test
    fun eachSideGathersWithTheLoginItsOwnServerHandsOutForThatPeer() = runTest {
        val caller = logins()
        val callee = logins()
        val (a, b) = connected(caller, callee)

        assertEquals(listOf(sam), caller.asked)
        assertEquals(listOf(frodo), callee.asked)
        assertEquals(listOf(stun, turn(1)), a.createdWith.single())
        assertEquals(listOf(stun, turn(1)), b.createdWith.single())
    }

    @Test
    fun aRingFetchesTheLoginSoAcceptingDoesNotWaitForIt() = runTest {
        val callee = logins(latencyMs = 2_000)
        val (_, b) = ringing(logins(), callee)
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
        val callee = logins(lifetimeMs = 60_000)
        val (_, b) = ringing(logins(), callee)
        advanceTimeBy(40_000)
        b.controller.accept()
        runCurrent()

        assertEquals(2, callee.asked.size)
        assertEquals(listOf(stun, turn(2)), b.createdWith.single())
    }

    @Test
    fun aRingWhoseRequestFailedAsksAgainOnAccept() = runTest {
        val callee = logins()
        callee.answer = { now -> stunOnly(now, fromServer = false) }
        val (_, b) = ringing(logins(), callee)
        callee.answer = null
        advanceTimeBy(10_000)
        b.controller.accept()
        runCurrent()

        assertEquals(2, callee.asked.size)
        assertEquals(listOf(stun, turn(2)), b.createdWith.single())
    }

    @Test
    fun aPlacedCallWhoseRequestFailedUsesTheFallbackWithoutAskingTwice() = runTest {
        val caller = logins()
        caller.answer = { now -> stunOnly(now, fromServer = false) }
        val (a, _) = ringing(caller, logins())

        assertEquals(1, caller.asked.size)
        assertEquals(listOf(stun), a.createdWith.single())
    }

    @Test
    fun aReconnectAfterTheLoginsExpiredGathersWithFreshOnesOnBothSides() = runTest {
        val (a, b) = connected(logins(), logins())

        advanceTimeBy(10 * 60_000)
        a.engine.simulateDrop()
        runCurrent()

        assertEquals(listOf(listOf(stun, turn(2))), a.engine.iceServerUpdates)
        assertEquals(listOf(listOf(stun, turn(2))), b.engine.iceServerUpdates)
        assertEquals(1, a.engine.restarts)
        assertEquals(1, b.engine.restarts)
    }

    @Test
    fun aReconnectWhileTheLoginIsFreshAsksForNothing() = runTest {
        val caller = logins()
        val callee = logins()
        val (a, b) = connected(caller, callee)

        a.engine.simulateDrop()
        runCurrent()

        assertEquals(1, a.engine.restarts)
        assertTrue(a.engine.iceServerUpdates.isEmpty())
        assertTrue(b.engine.iceServerUpdates.isEmpty())
        assertEquals(1, caller.asked.size)
        assertEquals(1, callee.asked.size)
    }

    @Test
    fun aServerWithoutTurnIsNotAskedAgainOnReconnect() = runTest {
        val caller = logins()
        caller.answer = { now -> stunOnly(now, fromServer = true) }
        val (a, _) = connected(caller, logins())

        advanceTimeBy(10 * 60_000)
        a.engine.simulateDrop()
        runCurrent()

        assertEquals(1, caller.asked.size)
        assertTrue(a.engine.iceServerUpdates.isEmpty())
    }

    @Test
    fun theServersAnswerIsUsedAsGiven() = runTest {
        val source = CallIceServerSource({ IceServersResponse(listOf(stun, turn(1)), UnixTimeUtc(1_000_000)) }, MutableStateFlow(emptyList()))
        val ice = source.forCall(sam)
        assertEquals(listOf(stun, turn(1)), ice.servers)
        assertEquals(1_000_000L, ice.expiresAtMs)
        assertTrue(ice.fromServer)
    }

    @Test
    fun aServerWithoutTurnGivesStunOnlyWithNoExpiry() = runTest {
        val ice = CallIceServerSource({ IceServersResponse(listOf(stun)) }, MutableStateFlow(emptyList())).forCall(sam)
        assertEquals(listOf(stun), ice.servers)
        assertEquals(null, ice.expiresAtMs)
        assertTrue(ice.fromServer)
    }

    @Test
    fun aFailedRequestFallsBackToTheHandshakeStun() = runTest {
        val ice = CallIceServerSource({ throw IllegalStateException("404") }, MutableStateFlow(stun.urls)).forCall(sam)
        assertEquals(listOf(stun), ice.servers)
        assertFalse(ice.fromServer)
    }

    @Test
    fun aSlowRequestAndALateHandshakeShareOneWait() = runTest {
        val ice = CallIceServerSource({ awaitCancellation() }, MutableStateFlow(emptyList()), timeoutMs = 3_000).forCall(sam)
        assertEquals(3_000, testScheduler.currentTime)
        assertEquals(emptyList(), ice.servers)
        assertFalse(ice.fromServer)
    }

    @Test
    fun aHandshakeThatArrivesWhileTheRequestHangsIsUsed() = runTest {
        val handshake = MutableStateFlow(emptyList<String>())
        val source = CallIceServerSource({ awaitCancellation() }, handshake, timeoutMs = 3_000)
        val ice = backgroundScope.async { source.forCall(sam) }
        advanceTimeBy(1_000)
        handshake.value = stun.urls
        advanceTimeBy(2_001)
        assertEquals(listOf(stun), ice.await().servers)
    }

    @Test
    fun aLoginNeverShowsInTheLog() {
        val text = CallIceServers(listOf(turn(7)), expiresAtMs = 1).toString() + turn(7).toString()
        assertFalse("secret-7" in text)
        assertFalse("login-7" in text)
    }
}
