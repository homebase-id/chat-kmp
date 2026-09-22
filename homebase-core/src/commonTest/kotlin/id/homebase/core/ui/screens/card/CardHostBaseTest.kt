package id.homebase.core.ui.screens.card

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class CardHostBaseTest {
    private val host = FakeCardHost()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        host.dispose()
        Dispatchers.resetMain()
    }

    @Test
    fun theCardPageComesFromTheOwnersIdentity() {
        assertEquals("https://frodo.dotyou.cloud/card?host=app", cardPageUrl("frodo.dotyou.cloud"))
        assertEquals(
            "https://frodo.dotyou.cloud/card?host=frame",
            cardPageUrl("frodo.dotyou.cloud", CardPageHost.FRAME),
        )
        assertEquals("https://frodo.dotyou.cloud", cardOrigin("frodo.dotyou.cloud"))
    }

    @Test
    fun loadingAPageLoadsTheCardUrl() {
        host.load()

        assertEquals(listOf("https://frodo.dotyou.cloud/card?host=app"), host.loads)
        assertFalse(host.isLoaded.value)
    }

    @Test
    fun renderBeforeLoadIsQueuedUntilLoaded() {
        host.render(payload(CardDesign.BOARD))
        assertTrue(host.sent.isEmpty())

        host.onBridgeMessage("""{"type":"loaded"}""")

        assertTrue(host.isLoaded.value)
        val render = assertIs<CardCommand.Render>(host.sent.single())
        assertTrue(render.script().startsWith("window.homebaseCard.render("))
    }

    @Test
    fun renderAfterLoadGoesStraightToThePage() {
        host.onBridgeMessage("""{"type":"loaded"}""")
        assertTrue(host.sent.isEmpty())

        host.render(payload(CardDesign.POSTER))

        assertEquals(1, host.sent.size)
    }

    @Test
    fun reloadedPageGetsTheLastCardAgain() {
        host.onBridgeMessage("""{"type":"loaded"}""")
        host.render(payload(CardDesign.POSTER))
        host.onBridgeMessage("""{"type":"ready","layout":"poster","ms":5}""")

        host.load()
        assertFalse(host.isLoaded.value)
        host.onBridgeMessage("""{"type":"loaded"}""")

        assertEquals(2, host.sent.size)
        assertEquals(host.sent[0], host.sent[1])
    }

    @Test
    fun exportPngBeforeLoadIsAnErrorNotASilentNoOp() = runTest {
        val next = async(start = CoroutineStart.UNDISPATCHED) { host.events.first() }

        host.exportPng()

        assertIs<CardEvent.Error>(next.await())
        assertTrue(host.sent.isEmpty())
    }

    @Test
    fun pageEventsAreForwarded() = runTest {
        val next = async(start = CoroutineStart.UNDISPATCHED) { host.events.first() }

        host.onBridgeMessage("""{"type":"link","href":"https://a.b"}""")

        assertEquals(CardEvent.Link("https://a.b"), next.await())
    }

    @Test
    fun unreadableEventSurfacesAsError() = runTest {
        val next = async(start = CoroutineStart.UNDISPATCHED) { host.events.first() }

        host.onBridgeMessage("{not json")

        assertIs<CardEvent.Error>(next.await())
    }

    @Test
    fun aPageThatNeverSaysLoadedIsReportedAsUnsupported() = runTest {
        val events = collectEvents()
        host.load()
        host.onMainFrameFinished()

        advanceTimeBy(CARD_LOADED_TIMEOUT - 1.milliseconds)
        runCurrent()
        assertTrue(events.isEmpty(), "$events")

        advanceTimeBy(2.milliseconds)
        runCurrent()
        assertTrue(assertIs<CardEvent.Error>(events.single()).unsupported)
        assertFalse(host.isLoaded.value)
    }

    @Test
    fun loadedInTimeCancelsTheUnsupportedTimeout() = runTest {
        val events = collectEvents()
        host.load()
        host.onMainFrameFinished()

        advanceTimeBy(CARD_LOADED_TIMEOUT / 2)
        host.onBridgeMessage("""{"type":"loaded"}""")
        advanceTimeBy(CARD_LOADED_TIMEOUT)
        runCurrent()

        assertEquals(listOf<CardEvent>(CardEvent.Loaded), events)
    }

    @Test
    fun aMainFrameFailureIsReportedOnceAndStartsNoTimeout() = runTest {
        val events = collectEvents()
        host.load()

        host.onMainFrameFailed("HTTP 404", unsupported = true)
        host.onMainFrameFailed("frame load interrupted", unsupported = false)
        host.onMainFrameFinished()
        advanceTimeBy(CARD_LOADED_TIMEOUT * 2)
        runCurrent()

        assertEquals(listOf<CardEvent>(CardEvent.Error("HTTP 404", unsupported = true)), events)
    }

    @Test
    fun aFreshLoadWaitsForItsOwnFinish() = runTest {
        val events = collectEvents()
        host.load()
        host.onMainFrameFailed("offline", unsupported = false)

        host.load()
        host.onMainFrameFinished()
        advanceTimeBy(CARD_LOADED_TIMEOUT + 1.milliseconds)
        runCurrent()

        assertEquals(listOf(false, true), events.map { assertIs<CardEvent.Error>(it).unsupported })
    }

    @Test
    fun disposingWhileWaitingForLoadedIsNotAnError() = runTest {
        val events = collectEvents()
        host.load()
        host.onMainFrameFinished()

        host.dispose()
        advanceTimeBy(CARD_LOADED_TIMEOUT * 2)
        runCurrent()

        assertTrue(events.isEmpty(), "$events")
    }

    private fun TestScope.collectEvents(): List<CardEvent> {
        val events = mutableListOf<CardEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { host.events.collect { events += it } }
        return events
    }

    private fun payload(design: String) = CardPayload(design = design, data = CardData(odinId = "frodo.dotyou.cloud"))

    private class FakeCardHost : CardHostBase(cardPageUrl("frodo.dotyou.cloud")) {
        val loads = mutableListOf<String>()
        val sent = mutableListOf<CardCommand>()

        fun load() = loadPage()

        override fun loadUrl(url: String) {
            loads += url
        }

        override fun send(command: CardCommand) {
            sent += command
        }

        override fun release() = Unit
    }
}
